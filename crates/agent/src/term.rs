use std::{
    io::{Read, Write},
    thread,
};

use anyhow::Result;
use portable_pty::{native_pty_system, Child, CommandBuilder, MasterPty, PtySize};
use remotectl_protocol::{b64_encode, Frame, Res};
use tokio::sync::mpsc::UnboundedSender;

pub struct Term {
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    child: Box<dyn Child + Send + Sync>,
}

fn size(cols: u16, rows: u16) -> PtySize {
    PtySize { rows: rows.clamp(2, 500), cols: cols.clamp(10, 500), pixel_width: 0, pixel_height: 0 }
}

impl Term {
    pub fn open(term_id: u32, cols: u16, rows: u16, out: UnboundedSender<Frame<Res>>) -> Result<Self> {
        let pair = native_pty_system().openpty(size(cols, rows))?;

        #[cfg(windows)]
        let mut cmd = CommandBuilder::new("powershell.exe");
        #[cfg(not(windows))]
        let mut cmd = {
            let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/zsh".into());
            let mut c = CommandBuilder::new(shell);
            c.arg("-l");
            c
        };
        cmd.env("TERM", "xterm-256color");
        if let Some(h) = directories::BaseDirs::new() {
            cmd.cwd(h.home_dir());
        }

        let child = pair.slave.spawn_command(cmd)?;
        drop(pair.slave);
        let mut reader = pair.master.try_clone_reader()?;
        let writer = pair.master.take_writer()?;

        thread::spawn(move || {
            let mut buf = [0u8; 8192];
            loop {
                match reader.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let frame = Frame { id: 0, body: Res::TermData { term_id, data: b64_encode(&buf[..n]) } };
                        if out.send(frame).is_err() {
                            return;
                        }
                    }
                }
            }
            let _ = out.send(Frame { id: 0, body: Res::TermExit { term_id, code: None } });
        });

        Ok(Self { master: pair.master, writer, child })
    }

    pub fn write(&mut self, data: &[u8]) -> Result<()> {
        self.writer.write_all(data)?;
        self.writer.flush()?;
        Ok(())
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<()> {
        self.master.resize(size(cols, rows))?;
        Ok(())
    }
}

impl Drop for Term {
    fn drop(&mut self) {
        let _ = self.child.kill();
    }
}
