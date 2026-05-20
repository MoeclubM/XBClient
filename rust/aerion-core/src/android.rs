use anyhow::{Context, Result, ensure};
use jni::objects::{Global, JClass};
use jni::{Env, JValue, JavaVM, jni_sig, jni_str};
use once_cell::sync::Lazy;
use std::sync::Mutex as StdMutex;

static PASS_VPN_SERVICE_CLASS: Lazy<StdMutex<Option<Global<JClass<'static>>>>> =
    Lazy::new(|| StdMutex::new(None));

pub fn initialize_android(env: &Env<'_>, service_class: &JClass<'_>) -> jni::errors::Result<()> {
    let global_class = env.new_global_ref(service_class)?;
    *PASS_VPN_SERVICE_CLASS
        .lock()
        .expect("XbClientVpnService class lock poisoned") = Some(global_class);
    aerion::socket_protect::set_socket_protector(protect_android_socket);
    Ok(())
}

fn protect_android_socket(fd: i32) -> Result<()> {
    let protected = JavaVM::singleton()
        .context("get Java VM for Android VPN socket protection")?
        .attach_current_thread(|env| -> Result<bool> {
            let service_class = PASS_VPN_SERVICE_CLASS
                .lock()
                .expect("XbClientVpnService class lock poisoned");
            let class = service_class
                .as_ref()
                .context("XbClientVpnService class has not been initialized")?;
            Ok(env
                .call_static_method(
                    class,
                    jni_str!("protectSocketFd"),
                    jni_sig!("(I)Z"),
                    &[JValue::Int(fd)],
                )?
                .z()?)
        })
        .map_err(|error| anyhow::anyhow!("protect Android socket fd {fd}: {error}"))?;
    ensure!(protected, "Android VPN socket protection returned false");
    Ok(())
}

pub fn on_log(level: &str, message: &str) -> Result<()> {
    JavaVM::singleton()
        .context("get Java VM for log callback")?
        .attach_current_thread(|env| -> Result<()> {
            let service_class = PASS_VPN_SERVICE_CLASS
                .lock()
                .expect("XbClientVpnService class lock poisoned");
            let class = service_class
                .as_ref()
                .context("XbClientVpnService class has not been initialized")?;
            let level = env.new_string(level).context("create Android log level")?;
            let message = env
                .new_string(message)
                .context("create Android log message")?;
            env.call_static_method(
                class,
                jni_str!("onLog"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)V"),
                &[
                    JValue::Object(level.as_ref()),
                    JValue::Object(message.as_ref()),
                ],
            )?;
            Ok(())
        })
        .map_err(|error| anyhow::anyhow!("callback Android log: {error}"))
}

pub fn on_event(event_json: &str) -> Result<()> {
    JavaVM::singleton()
        .context("get Java VM for event callback")?
        .attach_current_thread(|env| -> Result<()> {
            let service_class = PASS_VPN_SERVICE_CLASS
                .lock()
                .expect("XbClientVpnService class lock poisoned");
            let class = service_class
                .as_ref()
                .context("XbClientVpnService class has not been initialized")?;
            let event_json = env
                .new_string(event_json)
                .context("create Android event JSON")?;
            env.call_static_method(
                class,
                jni_str!("onEvent"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(event_json.as_ref())],
            )?;
            Ok(())
        })
        .map_err(|error| anyhow::anyhow!("callback Android event: {error}"))
}
