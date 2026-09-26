use std::{
    process::Command,
    time::{Duration, Instant},
};

use remotectl_protocol::{Battery, DiskInfo, ProcessInfo, Stats};
use sysinfo::{Disks, Networks, ProcessesToUpdate, System};

pub struct Collector {
    sys: System,
    disks: Disks,
    nets: Networks,
    battery: Option<(Instant, Option<Battery>)>,
}

impl Collector {
    pub fn new() -> Self {
        let mut sys = System::new();
        sys.refresh_cpu_usage();
        sys.refresh_memory();
        Self {
            sys,
            disks: Disks::new_with_refreshed_list(),
            nets: Networks::new_with_refreshed_list(),
            battery: None,
        }
    }

    pub fn snapshot(&mut self) -> Stats {
        self.sys.refresh_cpu_usage();
        self.sys.refresh_memory();
        self.disks.refresh(true);
        self.nets.refresh(true);

        let load = System::load_average();
        // macOS lists the same APFS volume as both "/" and "/System/Volumes/Data"; show each once.
        let mut disks: Vec<DiskInfo> = Vec::new();
        for d in self.disks.iter() {
            let m = d.mount_point().to_string_lossy().into_owned();
            if d.total_space() == 0 || (m.starts_with("/System/Volumes/") && m != "/System/Volumes/Data") {
                continue;
            }
            let (total, available) = (d.total_space(), d.available_space());
            if disks.iter().any(|x| x.total == total && x.available == available) {
                continue;
            }
            disks.push(DiskInfo { mount: m, total, available });
        }
        let (rx, tx) = self
            .nets
            .iter()
            .fold((0u64, 0u64), |(r, t), (_, n)| (r + n.total_received(), t + n.total_transmitted()));

        Stats {
            hostname: System::host_name().unwrap_or_default(),
            os: System::long_os_version().unwrap_or_else(|| std::env::consts::OS.into()),
            uptime_secs: System::uptime(),
            cpu_percent: self.sys.global_cpu_usage(),
            cpu_cores: self.sys.cpus().iter().map(|c| c.cpu_usage()).collect(),
            load_avg: [load.one, load.five, load.fifteen],
            mem_total: self.sys.total_memory(),
            mem_used: self.sys.used_memory(),
            swap_total: self.sys.total_swap(),
            swap_used: self.sys.used_swap(),
            disks,
            net_rx_bytes: rx,
            net_tx_bytes: tx,
            battery: self.battery(),
        }
    }

    /// Top processes by CPU. Needs two samples for meaningful CPU numbers.
    pub fn top_processes(&mut self, limit: usize) -> Vec<ProcessInfo> {
        self.sys.refresh_processes(ProcessesToUpdate::All, true);
        std::thread::sleep(sysinfo::MINIMUM_CPU_UPDATE_INTERVAL);
        self.sys.refresh_processes(ProcessesToUpdate::All, true);
        let mut v: Vec<ProcessInfo> = self
            .sys
            .processes()
            .values()
            .map(|p| ProcessInfo {
                pid: p.pid().as_u32(),
                name: p.name().to_string_lossy().into_owned(),
                cpu_percent: p.cpu_usage(),
                mem_bytes: p.memory(),
            })
            .collect();
        v.sort_by(|a, b| b.cpu_percent.total_cmp(&a.cpu_percent));
        v.truncate(limit);
        v
    }

    /// Battery lookups spawn a process, so cache them for 30s.
    fn battery(&mut self) -> Option<Battery> {
        if let Some((at, b)) = &self.battery {
            if at.elapsed() < Duration::from_secs(30) {
                return b.clone();
            }
        }
        let b = read_battery();
        self.battery = Some((Instant::now(), b.clone()));
        b
    }
}

#[cfg(target_os = "macos")]
fn read_battery() -> Option<Battery> {
    let out = Command::new("pmset").args(["-g", "batt"]).output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    let pct_end = text.find('%')?;
    let digits: String = text[..pct_end].chars().rev().take_while(|c| c.is_ascii_digit()).collect::<Vec<_>>().into_iter().rev().collect();
    Some(Battery { percent: digits.parse().ok()?, charging: text.contains("AC Power") })
}

#[cfg(windows)]
fn read_battery() -> Option<Battery> {
    let script = "$b = Get-CimInstance Win32_Battery | Select-Object -First 1; if ($b) { \"$($b.EstimatedChargeRemaining),$($b.BatteryStatus)\" }";
    let out = Command::new("powershell").args(["-NoProfile", "-Command", script]).output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    let (pct, status) = text.trim().split_once(',')?;
    Some(Battery { percent: pct.parse().ok()?, charging: status != "1" })
}

#[cfg(not(any(target_os = "macos", windows)))]
fn read_battery() -> Option<Battery> {
    let _ = Command::new("true");
    None
}
