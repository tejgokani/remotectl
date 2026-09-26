//! Installed applications: list, launch, quit. Ids come only from our own scan, never from
//! free-form phone input, so a request can only act on an app that is actually installed.

use anyhow::{anyhow, Result};
use remotectl_protocol::AppInfo;

#[cfg(target_os = "macos")]
mod imp {
    use super::*;
    use std::{
        collections::HashSet,
        path::{Path, PathBuf},
        process::Command,
    };
    use sysinfo::{ProcessesToUpdate, System};

    struct App {
        id: String,
        name: String,
        path: PathBuf,
    }

    fn roots() -> Vec<PathBuf> {
        let mut r = vec![
            PathBuf::from("/Applications"),
            PathBuf::from("/System/Applications"),
            PathBuf::from("/System/Applications/Utilities"),
            PathBuf::from("/Applications/Utilities"),
        ];
        if let Some(h) = directories::BaseDirs::new() {
            r.push(h.home_dir().join("Applications"));
        }
        r
    }

    fn read_app(path: &Path) -> Option<App> {
        let plist = plist::Value::from_file(path.join("Contents/Info.plist")).ok()?;
        let d = plist.as_dictionary()?;
        let get = |k: &str| d.get(k).and_then(|v| v.as_string()).map(str::to_owned);
        let id = get("CFBundleIdentifier")?;
        let name = get("CFBundleDisplayName")
            .or_else(|| get("CFBundleName"))
            .unwrap_or_else(|| path.file_stem().unwrap_or_default().to_string_lossy().into_owned());
        Some(App { id, name, path: path.to_path_buf() })
    }

    fn scan() -> Vec<App> {
        let mut seen = HashSet::new();
        let mut apps = Vec::new();
        for root in roots() {
            let Ok(rd) = std::fs::read_dir(&root) else { continue };
            for e in rd.flatten() {
                let p = e.path();
                if p.extension().is_some_and(|x| x == "app") {
                    if let Some(a) = read_app(&p) {
                        if seen.insert(a.id.clone()) {
                            apps.push(a);
                        }
                    }
                }
            }
        }
        apps.sort_by_key(|a| a.name.to_lowercase());
        apps
    }

    fn running_exes() -> Vec<PathBuf> {
        let mut sys = System::new();
        sys.refresh_processes(ProcessesToUpdate::All, true);
        sys.processes().values().filter_map(|p| p.exe().map(Path::to_path_buf)).collect()
    }

    pub fn list() -> Vec<AppInfo> {
        let exes = running_exes();
        scan()
            .into_iter()
            .map(|a| {
                let running = exes.iter().any(|e| e.starts_with(&a.path));
                AppInfo { id: a.id, name: a.name, running }
            })
            .collect()
    }

    pub fn launch(id: &str) -> Result<()> {
        let app = scan().into_iter().find(|a| a.id == id).ok_or_else(|| anyhow!("unknown app"))?;
        let st = Command::new("open").arg(&app.path).status()?;
        st.success().then_some(()).ok_or_else(|| anyhow!("open failed"))
    }

    pub fn quit(id: &str, force: bool) -> Result<()> {
        let app = scan().into_iter().find(|a| a.id == id).ok_or_else(|| anyhow!("unknown app"))?;
        if force {
            let mut sys = System::new();
            sys.refresh_processes(ProcessesToUpdate::All, true);
            let mut killed = false;
            for p in sys.processes().values() {
                if p.exe().is_some_and(|e| e.starts_with(&app.path)) {
                    killed |= p.kill();
                }
            }
            return killed.then_some(()).ok_or_else(|| anyhow!("not running"));
        }
        // The bundle id is passed as an argv item, never interpolated into the script.
        let st = Command::new("osascript")
            .args(["-e", "on run argv", "-e", "tell application id (item 1 of argv) to quit", "-e", "end run", &app.id])
            .status()?;
        st.success().then_some(()).ok_or_else(|| anyhow!("quit request failed"))
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use std::process::Command;

    fn ps(script: &str) -> Result<String> {
        let out = Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", script]).output()?;
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    }

    #[derive(serde::Deserialize)]
    #[serde(rename_all = "PascalCase")]
    struct StartApp {
        name: String,
        #[serde(rename = "AppID")]
        app_id: String,
    }

    fn scan() -> Vec<StartApp> {
        let raw = ps("Get-StartApps | ConvertTo-Json -Compress").unwrap_or_default();
        serde_json::from_str::<Vec<StartApp>>(&raw).unwrap_or_default()
    }

    pub fn list() -> Vec<AppInfo> {
        let running = ps("Get-Process | Where-Object {$_.MainWindowTitle} | ForEach-Object { $_.ProcessName }").unwrap_or_default();
        let running: Vec<String> = running.lines().map(|l| l.trim().to_lowercase()).collect();
        scan()
            .into_iter()
            .map(|a| {
                let n = a.name.to_lowercase();
                let running = running.iter().any(|p| n.contains(p.as_str()));
                AppInfo { id: a.app_id, name: a.name, running }
            })
            .collect()
    }

    pub fn launch(id: &str) -> Result<()> {
        let app = scan().into_iter().find(|a| a.app_id == id).ok_or_else(|| anyhow!("unknown app"))?;
        Command::new("explorer.exe").arg(format!("shell:AppsFolder\\{}", app.app_id)).spawn()?;
        Ok(())
    }

    pub fn quit(id: &str, force: bool) -> Result<()> {
        let app = scan().into_iter().find(|a| a.app_id == id).ok_or_else(|| anyhow!("unknown app"))?;
        // Best effort: match windowed processes by display name (name is passed as an env var, not interpolated).
        let action = if force { "$_.Kill()" } else { "[void]$_.CloseMainWindow()" };
        let script = format!(
            "Get-Process | Where-Object {{ $_.MainWindowTitle -and $env:RC_APP -like \"*$($_.ProcessName)*\" }} | ForEach-Object {{ {action} }}"
        );
        Command::new("powershell").env("RC_APP", app.name).args(["-NoProfile", "-Command", &script]).status()?;
        Ok(())
    }
}

#[cfg(not(any(target_os = "macos", windows)))]
mod imp {
    use super::*;
    pub fn list() -> Vec<AppInfo> {
        Vec::new()
    }
    pub fn launch(_: &str) -> Result<()> {
        Err(anyhow!("unsupported OS"))
    }
    pub fn quit(_: &str, _: bool) -> Result<()> {
        Err(anyhow!("unsupported OS"))
    }
}

pub use imp::{launch, list, quit};
