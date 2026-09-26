//! Noise_XX end-to-end channel. The relay forwards these bytes without being able to read them.

use anyhow::{anyhow, Result};
use snow::{Builder, HandshakeState, TransportState};

const PARAMS: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";
/// Noise messages max out at 65535 bytes; leave room for the 16-byte tag and our 1-byte flag.
const CHUNK: usize = 60_000;

pub struct Keypair {
    pub private: Vec<u8>,
    pub public: Vec<u8>,
}

pub fn generate_keypair() -> Result<Keypair> {
    let kp = Builder::new(PARAMS.parse()?).generate_keypair()?;
    Ok(Keypair { private: kp.private, public: kp.public })
}

pub fn initiator(private: &[u8]) -> Result<HandshakeState> {
    Ok(Builder::new(PARAMS.parse()?).local_private_key(private)?.build_initiator()?)
}

pub fn responder(private: &[u8]) -> Result<HandshakeState> {
    Ok(Builder::new(PARAMS.parse()?).local_private_key(private)?.build_responder()?)
}

/// Established encrypted channel with transparent fragmentation of large messages.
pub struct Channel {
    transport: TransportState,
    partial: Vec<u8>,
    pub remote_static: Vec<u8>,
}

impl Channel {
    pub fn from_handshake(hs: HandshakeState) -> Result<Self> {
        if !hs.is_handshake_finished() {
            return Err(anyhow!("handshake not finished"));
        }
        let remote_static = hs
            .get_remote_static()
            .ok_or_else(|| anyhow!("peer sent no static key"))?
            .to_vec();
        Ok(Self { transport: hs.into_transport_mode()?, partial: Vec::new(), remote_static })
    }

    /// Encrypt one logical message into one or more wire frames.
    pub fn seal(&mut self, plaintext: &[u8]) -> Result<Vec<Vec<u8>>> {
        let chunks: Vec<&[u8]> =
            if plaintext.is_empty() { vec![&[][..]] } else { plaintext.chunks(CHUNK).collect() };
        let last = chunks.len() - 1;
        let mut frames = Vec::with_capacity(chunks.len());
        for (i, c) in chunks.iter().enumerate() {
            let mut buf = Vec::with_capacity(c.len() + 1);
            buf.push(u8::from(i != last)); // 1 = more fragments follow
            buf.extend_from_slice(c);
            let mut out = vec![0u8; buf.len() + 16];
            let n = self.transport.write_message(&buf, &mut out)?;
            out.truncate(n);
            frames.push(out);
        }
        Ok(frames)
    }

    /// Decrypt one wire frame. Returns the full message once its last fragment arrives.
    pub fn open(&mut self, frame: &[u8]) -> Result<Option<Vec<u8>>> {
        let mut buf = vec![0u8; frame.len()];
        let n = self.transport.read_message(frame, &mut buf)?;
        buf.truncate(n);
        let (&flag, body) = buf.split_first().ok_or_else(|| anyhow!("empty frame"))?;
        self.partial.extend_from_slice(body);
        if self.partial.len() > 8 * 1024 * 1024 {
            return Err(anyhow!("message too large"));
        }
        if flag == 1 {
            return Ok(None);
        }
        Ok(Some(std::mem::take(&mut self.partial)))
    }
}
