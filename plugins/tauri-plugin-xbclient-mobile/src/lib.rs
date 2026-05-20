use serde::{Deserialize, Serialize};
use tauri::{Manager, Runtime, plugin::TauriPlugin};

#[cfg(mobile)]
use tauri::plugin::PluginHandle;
#[cfg(target_os = "android")]
const PLUGIN_IDENTIFIER: &str = "moe.telecom.xbclient.tauri.mobile";
#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_xbclient_mobile);

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RewardedAdRequest {
    pub ad_unit_id: String,
    pub user_id: String,
    pub custom_data: String,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RewardedAdResult {
    pub earned: bool,
    pub reward_type: String,
    pub reward_amount: i64,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppOpenAdRequest {
    pub ad_unit_id: String,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppOpenAdResult {
    pub shown: bool,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OAuthCallbackResult {
    pub url: String,
}

#[derive(Debug, Serialize)]
struct EmptyPayload {}

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[cfg(mobile)]
    #[error(transparent)]
    Mobile(#[from] tauri::plugin::mobile::PluginInvokeError),
    #[cfg(desktop)]
    #[error("AdMob is only available on Android and iOS")]
    UnsupportedPlatform,
    #[cfg(not(target_os = "android"))]
    #[error("OAuth callback bridge is only available on Android")]
    UnsupportedOAuthCallbackPlatform,
    #[cfg(not(target_os = "android"))]
    #[error("Android VPN bridge is only available on Android")]
    UnsupportedAndroidVpnPlatform,
}

type Result<T> = std::result::Result<T, Error>;

pub struct XbClientMobile<R: Runtime> {
    #[cfg(not(mobile))]
    _marker: std::marker::PhantomData<fn() -> R>,
    #[cfg(mobile)]
    mobile_plugin_handle: PluginHandle<R>,
}

impl<R: Runtime> XbClientMobile<R> {
    pub async fn show_rewarded_ad(&self, request: RewardedAdRequest) -> Result<RewardedAdResult> {
        #[cfg(mobile)]
        {
            return self
                .mobile_plugin_handle
                .run_mobile_plugin_async("showRewardedAd", request)
                .await
                .map_err(Into::into);
        }

        #[cfg(desktop)]
        {
            let _ = request;
            Err(Error::UnsupportedPlatform)
        }
    }

    pub async fn show_app_open_ad(&self, request: AppOpenAdRequest) -> Result<AppOpenAdResult> {
        #[cfg(mobile)]
        {
            return self
                .mobile_plugin_handle
                .run_mobile_plugin_async("showAppOpenAd", request)
                .await
                .map_err(Into::into);
        }

        #[cfg(desktop)]
        {
            let _ = request;
            Err(Error::UnsupportedPlatform)
        }
    }

    pub async fn take_oauth_callback(&self) -> Result<OAuthCallbackResult> {
        #[cfg(target_os = "android")]
        {
            return self
                .mobile_plugin_handle
                .run_mobile_plugin_async("takeOAuthCallback", EmptyPayload {})
                .await
                .map_err(Into::into);
        }

        #[cfg(not(target_os = "android"))]
        {
            Err(Error::UnsupportedOAuthCallbackPlatform)
        }
    }

    pub async fn start_vpn(&self, request: serde_json::Value) -> Result<serde_json::Value> {
        #[cfg(target_os = "android")]
        {
            return self
                .mobile_plugin_handle
                .run_mobile_plugin_async("startVpn", request)
                .await
                .map_err(Into::into);
        }

        #[cfg(not(target_os = "android"))]
        {
            let _ = request;
            Err(Error::UnsupportedAndroidVpnPlatform)
        }
    }

    pub async fn stop_vpn(&self) -> Result<serde_json::Value> {
        #[cfg(target_os = "android")]
        {
            return self
                .mobile_plugin_handle
                .run_mobile_plugin_async("stopVpn", EmptyPayload {})
                .await
                .map_err(Into::into);
        }

        #[cfg(not(target_os = "android"))]
        {
            Err(Error::UnsupportedAndroidVpnPlatform)
        }
    }

    pub async fn get_vpn_state(&self) -> Result<serde_json::Value> {
        #[cfg(target_os = "android")]
        {
            return self
                .mobile_plugin_handle
                .run_mobile_plugin_async("getVpnState", EmptyPayload {})
                .await
                .map_err(Into::into);
        }

        #[cfg(not(target_os = "android"))]
        {
            Err(Error::UnsupportedAndroidVpnPlatform)
        }
    }
}

pub trait XbClientMobileExt<R: Runtime> {
    fn xbclient_mobile(&self) -> &XbClientMobile<R>;
}

impl<R: Runtime, T: Manager<R>> XbClientMobileExt<R> for T {
    fn xbclient_mobile(&self) -> &XbClientMobile<R> {
        self.state::<XbClientMobile<R>>().inner()
    }
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    tauri::plugin::Builder::<R>::new("xbclient-mobile")
        .setup(|app, _api| {
            #[cfg(target_os = "android")]
            let handle = _api.register_android_plugin(PLUGIN_IDENTIFIER, "XbClientMobilePlugin")?;
            #[cfg(target_os = "ios")]
            let handle = _api.register_ios_plugin(init_plugin_xbclient_mobile)?;

            app.manage(XbClientMobile {
                #[cfg(not(mobile))]
                _marker: std::marker::PhantomData::<fn() -> R>,
                #[cfg(mobile)]
                mobile_plugin_handle: handle,
            });
            Ok(())
        })
        .build()
}
