use anyhow::{anyhow, Result};

#[cfg(target_os = "macos")]
pub fn lock_screen() -> Result<()> {
    // Same call the "Lock Screen" menu item uses. It's a private symbol, so fall back if it disappears.
    unsafe {
        let lib = libc::dlopen(
            c"/System/Library/PrivateFrameworks/login.framework/Versions/Current/login".as_ptr(),
            libc::RTLD_LAZY,
        );
        if !lib.is_null() {
            let sym = libc::dlsym(lib, c"SACLockScreenImmediate".as_ptr());
            if !sym.is_null() {
                let f: extern "C" fn() -> i32 = std::mem::transmute(sym);
                f();
                return Ok(());
            }
        }
    }
    // Sleeps the display; locks only if "require password immediately" is enabled.
    let ok = std::process::Command::new("pmset").arg("displaysleepnow").status()?.success();
    ok.then_some(()).ok_or_else(|| anyhow!("could not lock screen"))
}

#[cfg(windows)]
pub fn lock_screen() -> Result<()> {
    let ok = std::process::Command::new("rundll32.exe").args(["user32.dll,LockWorkStation"]).status()?.success();
    ok.then_some(()).ok_or_else(|| anyhow!("could not lock workstation"))
}

#[cfg(not(any(target_os = "macos", windows)))]
pub fn lock_screen() -> Result<()> {
    Err(anyhow!("unsupported OS"))
}
