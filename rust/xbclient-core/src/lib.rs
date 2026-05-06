mod anytls;
mod hysteria2;

use anyhow::{Context, Result};
use jni::errors::{Result as JniResult, ThrowRuntimeExAndDefault};
#[cfg(target_os = "android")]
use jni::objects::JClass;
use jni::objects::{JObject, JString};
use jni::{Env, EnvUnowned};
use once_cell::sync::Lazy;
use serde_json::json;
use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};
use tokio::runtime::Runtime;

static RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("XBClient")
            .with_max_level(log::LevelFilter::Info),
    );
    let _ = rustls::crypto::ring::default_provider().install_default();
    Runtime::new().expect("create xbclient tokio runtime")
});

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_telecom_xbclient_RustCore_initializeAndroid<'local>(
    mut env: EnvUnowned<'local>,
    _object: JObject<'local>,
    service_class: JClass<'local>,
) {
    env.with_env(|env| -> JniResult<()> {
        anytls::initialize_android(env, &service_class)?;
        hysteria2::initialize_android(env, &service_class)
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_telecom_xbclient_RustCore_startAnyTlsVpn<'local>(
    mut env: EnvUnowned<'local>,
    _object: JObject<'local>,
    input: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> JniResult<_> {
        call_string(env, &input, |value| {
            RUNTIME.block_on(start_vpn_from_json(&value))
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_telecom_xbclient_RustCore_testAnyTlsNode<'local>(
    mut env: EnvUnowned<'local>,
    _object: JObject<'local>,
    input: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> JniResult<_> {
        call_string(env, &input, |value| {
            RUNTIME.block_on(test_node_from_json(&value))
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_telecom_xbclient_RustCore_stopAnyTlsVpn<'local>(
    mut env: EnvUnowned<'local>,
    _object: JObject<'local>,
    session_id: i64,
) -> JString<'local> {
    env.with_env(|env| -> JniResult<_> {
        let output = match catch_unwind(AssertUnwindSafe(|| {
            RUNTIME.block_on(stop_vpn(session_id as u64))
        })) {
            Ok(Ok(value)) => value,
            Ok(Err(error)) => json!({"ok": false, "error": format_error_chain(&error)}).to_string(),
            Err(payload) => {
                json!({"ok": false, "error": format!("Rust panic: {}", panic_message(payload))})
                    .to_string()
            }
        };
        JString::from_str(env, output)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

const HYSTERIA2_SESSION_MASK: u64 = 1 << 62;

async fn start_vpn_from_json(input: &str) -> Result<String> {
    if request_protocol(input)?.eq_ignore_ascii_case("hysteria2") {
        let output = hysteria2::start_vpn_from_json(input).await?;
        let mut value: serde_json::Value =
            serde_json::from_str(&output).context("parse Hysteria2 VPN start response")?;
        let session_id = value
            .get("session_id")
            .and_then(serde_json::Value::as_u64)
            .context("Hysteria2 VPN start response missing session_id")?;
        value["session_id"] = json!(session_id | HYSTERIA2_SESSION_MASK);
        return Ok(value.to_string());
    }
    anytls::start_vpn_from_json(input).await
}

async fn test_node_from_json(input: &str) -> Result<String> {
    if request_protocol(input)?.eq_ignore_ascii_case("hysteria2") {
        return hysteria2::test_node_from_json(input).await;
    }
    anytls::test_node_from_json(input).await
}

async fn stop_vpn(session_id: u64) -> Result<String> {
    if session_id & HYSTERIA2_SESSION_MASK != 0 {
        return hysteria2::stop_vpn(session_id & !HYSTERIA2_SESSION_MASK).await;
    }
    anytls::stop_vpn(session_id).await
}

fn request_protocol(input: &str) -> Result<String> {
    let value: serde_json::Value = serde_json::from_str(input).context("parse core request")?;
    let node = value.get("node").context("core request node is required")?;
    let protocol = node
        .get("type")
        .or_else(|| node.get("protocol"))
        .or_else(|| node.get("network"))
        .and_then(serde_json::Value::as_str)
        .unwrap_or("anytls");
    Ok(if protocol.eq_ignore_ascii_case("hy2") {
        "hysteria2".to_string()
    } else {
        protocol.to_ascii_lowercase()
    })
}

fn call_string<'local>(
    env: &mut Env<'local>,
    input: &JString<'local>,
    f: impl FnOnce(String) -> Result<String>,
) -> JniResult<JString<'local>> {
    let output = match catch_unwind(AssertUnwindSafe(|| {
        input
            .try_to_string(env)
            .context("read JNI string")
            .and_then(f)
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"ok": false, "error": format_error_chain(&error)}).to_string(),
        Err(payload) => {
            json!({"ok": false, "error": format!("Rust panic: {}", panic_message(payload))})
                .to_string()
        }
    };
    JString::from_str(env, output)
}

fn format_error_chain(error: &anyhow::Error) -> String {
    error
        .chain()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join(": ")
}

fn panic_message(payload: Box<dyn Any + Send + 'static>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        return (*message).to_string();
    }
    if let Some(message) = payload.downcast_ref::<String>() {
        return message.clone();
    }
    "unknown panic payload".to_string()
}
