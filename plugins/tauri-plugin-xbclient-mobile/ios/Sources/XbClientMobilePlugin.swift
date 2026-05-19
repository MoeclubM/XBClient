import Foundation
import GoogleMobileAds
import SwiftRs
import Tauri
import UIKit
import WebKit

struct RewardedAdArgs: Decodable {
  let adUnitId: String
  let userId: String
  let customData: String
}

struct AppOpenAdArgs: Decodable {
  let adUnitId: String
}

final class RewardedAdCall: NSObject, FullScreenContentDelegate {
  private let invoke: Invoke
  private let finish: () -> Void
  private var completed = false

  init(invoke: Invoke, finish: @escaping () -> Void) {
    self.invoke = invoke
    self.finish = finish
  }

  func earned(type: String, amount: Int) {
    if completed { return }
    completed = true
    let result: JSObject = [
      "earned": true,
      "rewardType": type,
      "rewardAmount": amount
    ]
    invoke.resolve(result)
  }

  func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
    if !completed {
      completed = true
      invoke.reject("Rewarded ad dismissed before reward")
    }
    finish()
  }

  func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
    if !completed {
      completed = true
      invoke.reject("Rewarded ad failed to show: \(error.localizedDescription)")
    }
    finish()
  }
}

final class AppOpenAdCall: NSObject, FullScreenContentDelegate {
  private let invoke: Invoke
  private let finish: () -> Void
  private var completed = false

  init(invoke: Invoke, finish: @escaping () -> Void) {
    self.invoke = invoke
    self.finish = finish
  }

  func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
    if !completed {
      completed = true
      let result: JSObject = ["shown": true]
      invoke.resolve(result)
    }
    finish()
  }

  func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
    if !completed {
      completed = true
      invoke.reject("App open ad failed to show: \(error.localizedDescription)")
    }
    finish()
  }
}

class XbClientMobilePlugin: Plugin {
  private var rewardedCalls: [RewardedAdCall] = []
  private var appOpenCalls: [AppOpenAdCall] = []
  private var initialized = false

  @objc public func showRewardedAd(_ invoke: Invoke) throws {
    let args: RewardedAdArgs
    do {
      args = try invoke.parseArgs(RewardedAdArgs.self)
      try startAdMob()
    } catch {
      invoke.reject(error.localizedDescription)
      return
    }
    Task { @MainActor in
      do {
        let ad = try await RewardedAd.load(with: args.adUnitId, request: Request())
        if !args.customData.isEmpty {
          let options = ServerSideVerificationOptions()
          options.customRewardText = args.customData
          ad.serverSideVerificationOptions = options
        }
        var call: RewardedAdCall!
        call = RewardedAdCall(invoke: invoke) { [weak self, weak call] in
          if let call = call {
            self?.rewardedCalls.removeAll { $0 === call }
          }
        }
        rewardedCalls.append(call)
        ad.fullScreenContentDelegate = call
        ad.present(from: manager.viewController) {
          let reward = ad.adReward
          call.earned(type: reward.type, amount: reward.amount.intValue)
        }
      } catch {
        invoke.reject("Rewarded ad failed to load: \(error.localizedDescription)")
      }
    }
  }

  @objc public func showAppOpenAd(_ invoke: Invoke) throws {
    let args: AppOpenAdArgs
    do {
      args = try invoke.parseArgs(AppOpenAdArgs.self)
      try startAdMob()
    } catch {
      invoke.reject(error.localizedDescription)
      return
    }
    Task { @MainActor in
      do {
        let ad = try await AppOpenAd.load(with: args.adUnitId, request: Request())
        var call: AppOpenAdCall!
        call = AppOpenAdCall(invoke: invoke) { [weak self, weak call] in
          if let call = call {
            self?.appOpenCalls.removeAll { $0 === call }
          }
        }
        appOpenCalls.append(call)
        ad.fullScreenContentDelegate = call
        ad.present(from: manager.viewController)
      } catch {
        invoke.reject("App open ad failed to load: \(error.localizedDescription)")
      }
    }
  }

  private func startAdMob() throws {
    if initialized { return }
    let appId = Bundle.main.object(forInfoDictionaryKey: "GADApplicationIdentifier") as? String ?? ""
    if appId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      throw NSError(domain: "XbClientMobilePlugin", code: 1, userInfo: [NSLocalizedDescriptionKey: "Tauri iOS AdMob App ID is missing from GADApplicationIdentifier"])
    }
    MobileAds.shared.start()
    initialized = true
  }
}

@_cdecl("init_plugin_xbclient_mobile")
func initPlugin() -> Plugin {
  return XbClientMobilePlugin()
}
