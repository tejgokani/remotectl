//! Auto-start registration. Deliberately per-user (LaunchAgent / logon task), not a system
//! service: the agent must live in your GUI session to lock the screen and launch apps, and
//! it should never run with more privilege than you have.

use anyhow::{bail, Result};
#[cfg(target_os = "macos")]
use anyhow::anyhow;

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

/// Poll the agent's log for a few seconds and report whether it actually reached the relay,
/// instead of just assuming the OS accepted the auto-start registration. Catches the case where
/// the service was registered but the process itself failed or crashed immediately.
fn confirm_started(log: &std::path::Path) {
    use std::time::{Duration, Instant};
    let deadline = Instant::now() + Duration::from_secs(6);
    while Instant::now() < deadline {
        if let Ok(text) = std::fs::read_to_string(log) {
            if text.contains("connected to relay") {
                println!("confirmed: the agent connected to the relay");
                return;
            }
            if let Some(line) = text.lines().rev().find(|l| l.contains("ERROR") || l.contains("WARN")) {
                println!("started, but its log shows a problem:\n  {}", line.trim());
                return;
            }
        }
        std::thread::sleep(Duration::from_millis(300));
    }
    println!("started, but hasn't confirmed a relay connection yet (may still be starting) — check:\n  {}", log.display());
}

#[cfg(target_os = "macos")]
pub fn install() -> Result<()> {
    use std::process::Command;
    let exe = agent_exe()?;
    let log = config::log_path()?;
    let _ = std::fs::remove_file(&log); // stale content would confuse confirm_started below
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
    confirm_started(&log);
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
    let log = config::log_path()?;
    let _ = std::fs::remove_file(&log);
    // `run --background` frees its own console right after starting, so Task Scheduler's usual
    // console window for a logon task disappears instantly instead of sitting there as something
    // that kills the agent if closed. It logs to file, since nothing is watching a console.
    let tr = format!("\"{exe}\" run --background");
    let st = Command::new("schtasks")
        .args(["/create", "/tn", "remotectl", "/tr", &tr, "/sc", "onlogon", "/rl", "limited", "/f"])
        .status()?;
    if !st.success() {
        bail!(
            "schtasks /create failed (exit {:?}). A locked-down or managed laptop may block Task \
             Scheduler; you can still run the agent by hand with:\n  \"{exe}\" run",
            st.code()
        );
    }
    // End any instance from a previous `install` or a manually-started `run` first, so two
    // agents never fight over the same relay room.
    let _ = Command::new("schtasks").args(["/end", "/tn", "remotectl"]).status();
    let st2 = Command::new("schtasks").args(["/run", "/tn", "remotectl"]).status()?;
    if !st2.success() {
        bail!("schtasks created the task but `/run` failed to start it (exit {:?})", st2.code());
    }
    println!("installed: starts at logon (no window to accidentally close) and is running now\n  log: {}", log.display());
    confirm_started(&log);
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
    Err(anyhow::anyhow!("auto-start is only implemented for macOS and Windows; run `remotectl run` manually"))
}

#[cfg(not(any(target_os = "macos", windows)))]
pub fn uninstall() -> Result<()> {
    Err(anyhow::anyhow!("unsupported OS"))
}
