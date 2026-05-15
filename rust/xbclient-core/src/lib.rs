mod aerion_config_compat;
mod aerion_core;
mod aerion_protocol;
#[cfg(target_os = "android")]
mod android;

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
    env.with_env(|env| -> JniResult<()> { android::initialize_android(env, &service_class) })
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

async fn start_vpn_from_json(input: &str) -> Result<String> {
    aerion_core::start_vpn_from_json(input).await
}

async fn test_node_from_json(input: &str) -> Result<String> {
    aerion_core::test_node_from_json(input).await
}

async fn stop_vpn(session_id: u64) -> Result<String> {
    aerion_core::stop_vpn(session_id).await
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
