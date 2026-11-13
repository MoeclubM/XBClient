pub use neto_ffi::*;

use anyhow::{Context, Result};
use once_cell::sync::Lazy;
use serde_json::{Value, json};
use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex};
use xbclient_panel::{BuildConfig, XbPanelClient};

static GLOBAL_PANEL: Lazy<Mutex<Option<Arc<XbPanelClient>>>> = Lazy::new(|| Mutex::new(None));

fn cstr_str<'a>(ptr: *const c_char) -> Result<&'a str> {
    if ptr.is_null() {
        return Ok("");
    }
    Ok(unsafe { CStr::from_ptr(ptr) }.to_str()?)
}

fn to_cstr(text: String) -> *mut c_char {
    CString::new(text.replace('\0', ""))
        .unwrap_or_else(|_| CString::new("{\"ok\":false,\"error\":\"CString nul\"}").unwrap())
        .into_raw()
}

pub fn global_panel() -> Result<Arc<XbPanelClient>> {
    GLOBAL_PANEL
        .lock()
        .expect("panel lock poisoned")
        .clone()
        .context("xbclient panel has not been initialized")
}

#[unsafe(no_mangle)]
pub extern "C" fn xbclient_panel_init(data_dir: *const c_char) -> *mut c_char {
    let res = catch_unwind(AssertUnwindSafe(|| -> Result<Value> {
        let dir = cstr_str(data_dir)?;
        let cfg = BuildConfig::from_compile_env();
        let neto = neto_ffi::initialize_controller(dir, &cfg.app_name, &cfg.user_agent)?;
        let mut guard = GLOBAL_PANEL.lock().expect("panel lock poisoned");
        let panel = match guard.as_ref() {
            Some(existing) => existing.clone(),
            None => {
                let created = XbPanelClient::new(dir, neto, cfg)?;
                *guard = Some(created.clone());
                created
            }
        };
        Ok(json!({
            "panel": panel.snapshot(),
            "neto": panel.neto().snapshot(),
        }))
    }));
    let out = match res {
        Ok(Ok(v)) => json!({ "ok": true, "result": v }).to_string(),
        Ok(Err(e)) => json!({ "ok": false, "error": format!("{e:#}") }).to_string(),
        Err(_) => json!({ "ok": false, "error": "Rust panic in xbclient_panel_init" }).to_string(),
    };
    to_cstr(out)
}

#[unsafe(no_mangle)]
pub extern "C" fn xbclient_keep_neto_symbols() -> usize {
    (neto_ffi::neto_init as *const () as usize)
        ^ (neto_ffi::neto_call as *const () as usize)
        ^ (neto_ffi::neto_shutdown as *const () as usize)
        ^ (neto_ffi::neto_free_string as *const () as usize)
}

#[unsafe(no_mangle)]
pub extern "C" fn xbclient_panel_call(
    method: *const c_char,
    params_json: *const c_char,
) -> *mut c_char {
    let res = catch_unwind(AssertUnwindSafe(|| -> Result<Value> {
        let m = cstr_str(method)?;
        let p_raw = cstr_str(params_json)?;
        let params: Value = if p_raw.trim().is_empty() {
            json!({})
        } else {
            serde_json::from_str(p_raw).context("parse params JSON")?
        };
        let panel = global_panel()?;
        neto_ffi::TOKIO_RUNTIME.block_on(panel.dispatch_json(m, params))
    }));
    let out = match res {
        Ok(Ok(v)) => json!({ "ok": true, "result": v }).to_string(),
        Ok(Err(e)) => json!({ "ok": false, "error": format!("{e:#}") }).to_string(),
        Err(_) => json!({ "ok": false, "error": "Rust panic in xbclient_panel_call" }).to_string(),
    };
    to_cstr(out)
}
