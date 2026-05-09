use anyhow::{Context, Result, ensure};
use jni::objects::{Global, JClass};
use jni::{Env, JValue, JavaVM, jni_sig, jni_str};
use once_cell::sync::Lazy;
use std::sync::Mutex as StdMutex;

static PASS_VPN_SERVICE_CLASS: Lazy<StdMutex<Option<Global>>> = Lazy::new(|| StdMutex::new(None));

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
