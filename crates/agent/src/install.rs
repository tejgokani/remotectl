//! Auto-start registration. Deliberately per-user (LaunchAgent / logon task), not a system
//! service: the agent must live in your GUI session to lock the screen and launch apps, and
//! it should never run with more privilege than you have.

use anyhow::{anyhow, bail, Result};

use crate::config;

/// Path to record in the service definition. Prefer Homebrew's stable `opt` symlink so the
/// service survives `brew upgrade` (the versioned Cellar path disappears).
fn agent_exe() -> Result<String> {
    let exe = std::env::current_exe()?.canonicalize()?;
    let s = exe.to_string_lossy().into_owned();
    if let Some(idx) = s.find("/Cellar/") {
        let prefix = &s[..idx];
        let opt = format!("{prefix}/opt/remotectl/bin/remotectl");
        if std::path::Path::new(&opt).exists() {
            return Ok(opt);
        }
    }
    Ok(s)
}

#[cfg(target_os = "macos")]
const LABEL: &str = "dev.remotectl.agent";

#[cfg(target_os = "macos")]
fn plist_path() -> Result<std::path::PathBuf> {
    let home = directories::BaseDirs::new().ok_or_else(|| anyhow!("no home dir"))?;
    Ok(home.home_dir().join("Library/LaunchAgents").join(format!("{LABEL}.plist")))
}

#[cfg(target_os = "macos")]
fn uid() -> u32 {
    unsafe { libc::getuid() }
}

#[cfg(target_os = "macos")]
pub fn install() -> Result<()> {
    use std::process::Command;
    let exe = agent_exe()?;
    let log = config::log_path()?;
    let plist = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>{LABEL}</string>
  <key>ProgramArguments</key><array><string>{exe}</string><string>run</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>{log}</string>
  <key>StandardErrorPath</key><string>{log}</string>
</dict>
</plist>
"#,
        log = log.display()
    );
    let path = plist_path()?;
    std::fs::create_dir_all(path.parent().unwrap())?;
    std::fs::write(&path, plist)?;

    let domain = format!("gui/{}", uid());
    // Replace any running copy, then load.
    let _ = Command::new("launchctl")
        .args(["bootout", &format!("{domain}/{LABEL}")])
        .stderr(std::process::Stdio::null())
        .status();
    let st = Command::new("launchctl").args(["bootstrap", &domain]).arg(&path).status()?;
    if !st.success() {
        bail!("launchctl bootstrap failed");
    }
    println!("installed: starts at login and is running now\n  log: {}", log.display());
    Ok(())
}

#[cfg(target_os = "macos")]
pub fn uninstall() -> Result<()> {
    let _ = std::process::Command::new("launchctl")
        .args(["bootout", &format!("gui/{}/{LABEL}", uid())])
        .stderr(std::process::Stdio::null())
        .status();
    let path = plist_path()?;
    if path.exists() {
        std::fs::remove_file(&path)?;
    }
    println!("uninstalled auto-start (config and pairings kept in ~/.remotectl)");
    Ok(())
}

#[cfg(windows)]
pub fn install() -> Result<()> {
    use std::process::Command;
    let exe = agent_exe()?;
    let tr = format!("\"{exe}\" run");
    let st = Command::new("schtasks")
        .args(["/create", "/tn", "remotectl", "/tr", &tr, "/sc", "onlogon", "/rl", "limited", "/f"])
        .status()?;
    if !st.success() {
        bail!("schtasks /create failed");
    }
    let _ = Command::new("schtasks").args(["/run", "/tn", "remotectl"]).status();
    println!("installed: starts at logon and is running now");
    Ok(())
}

#[cfg(windows)]
pub fn uninstall() -> Result<()> {
    let _ = std::process::Command::new("schtasks").args(["/end", "/tn", "remotectl"]).status();
    let _ = std::process::Command::new("schtasks").args(["/delete", "/tn", "remotectl", "/f"]).status();
    println!("uninstalled auto-start (config and pairings kept in ~/.remotectl)");
    Ok(())
}

#[cfg(not(any(target_os = "macos", windows)))]
pub fn install() -> Result<()> {
    let _ = (agent_exe()?, config::log_path()?);
    Err(anyhow!("auto-start is only implemented for macOS and Windows; run `remotectl run` manually"))
}

#[cfg(not(any(target_os = "macos", windows)))]
pub fn uninstall() -> Result<()> {
    Err(anyhow!("unsupported OS"))
}
