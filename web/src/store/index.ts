import type { RuntimeCapabilities, RuntimeConfig } from '../api/system'
import {
  DEFAULT_DIRECT_DNS,
  DEFAULT_NODE_DNS,
  DEFAULT_NODE_TEST_TARGET,
  DEFAULT_OVERSEAS_DNS,
  DEFAULT_VIRTUAL_DNS_POOL,
} from '../nodes'

export interface AppNode {
  protocol: string
  protocolLabel: string
  name: string
  host: string
  port: number
  tags: string[]
  connectSupported: boolean
  rawJson: string
  latencyMs?: number
  testError?: string
  _testing?: boolean
}

export interface VpnSession {
  sessionId: number
  socksAddr: string
  nodeIndex: number
  uploadBytes: number
  downloadBytes: number
}

export type RoutingMode = 'rule' | 'global' | 'direct'

export interface AppSettings {
  autoApplyProxy: boolean
  autostart: boolean
  silentStart: boolean
  routingMode: RoutingMode
  tunEnabled: boolean
  systemProxyEnabled: boolean
  nodeDns: string
  overseasDns: string
  directDns: string
  nodeTestTarget: string
  vpnDnsMode: 'virtual' | 'over_tcp' | 'direct'
  virtualDnsPool: string
  vpnIpv6Enabled: boolean
  appRuleMode: 'exclude' | 'allow'
  excludedApps: string
  allowedApps: string
  themeMode: 'system' | 'light' | 'dark'
  appLanguage: 'system' | 'zh-CN' | 'en' | 'ja' | 'ru' | 'fa'
}

export interface PlanPrice {
  field: string
  label: string
  amount: number
}

export interface PlanItem {
  id: number
  name: string
  content: string
  transferEnable: number
  prices: PlanPrice[]
}

export interface InviteItem {
  code: string
  status: number
}

export interface NoticeItem {
  id: number
  title: string
  content: string
  createdAt: number
}

export interface OAuthProvider {
  driver: string
  label: string
}

export interface AdRewardLogItem {
  id: number
  scene: string
  transactionId: string
  status: string
  error: string
  rewardContent: string
  usedAt: number
  createdAt: number
}

export interface SubscriptionState {
  summary: string
  blockReason: '' | 'no_plan' | 'expired' | 'traffic_exceeded'
  trafficUsedBytes: number
  trafficTotalBytes: number
  planName: string
  expiredAt: number
}

interface AppState {
  baseUrl: string
  authData: string
  email: string
  subscribeUrl: string
  nodes: AppNode[]
  vpn: VpnSession | null
  preferredNodeIndex: number
  systemProxyActive: boolean
  settings: AppSettings
  capabilities: RuntimeCapabilities | null
  buildConfig: RuntimeConfig | null
  balance: number
  commissionBalance: number
  currencySymbol: string
  currencyUnit: string
  paymentEnabled: boolean
  admobCloudEnabled: boolean
  planRewardAdEnabled: boolean
  pointsRewardAdEnabled: boolean
  appOpenAdEnabled: boolean
  planRewardedAdUnitId: string
  planRewardSsvUserId: string
  planRewardSsvCustomData: string
  pointsRewardedAdUnitId: string
  pointsRewardSsvUserId: string
  pointsRewardSsvCustomData: string
  appOpenAdUnitId: string
  githubProjectUrl: string
  inviteForce: boolean
  inviteCommissionRate: number
  inviteCommissionBalance: number
  oauthProviders: OAuthProvider[]
  registerEmailVerifyEnabled: boolean
  registerCaptchaEnabled: boolean
  adRewardLogs: AdRewardLogItem[]
  plans: PlanItem[]
  invites: InviteItem[]
  notices: NoticeItem[]
  subscription: SubscriptionState
  setSession(s: { baseUrl: string; authData: string; email: string }): void
  setSubscribe(s: { subscribeUrl: string; nodes: AppNode[] }): void
  setNodeResult(index: number, result: { latencyMs?: number; testError?: string }): void
  setNodeLoading(index: number): void
  setVpn(session: VpnSession | null): void
  setPreferredNodeIndex(index: number): void
  setSystemProxyActive(active: boolean): void
  updateVpnTraffic(sessionId: number, uploadBytes: number, downloadBytes: number): void
  setSettings(patch: Partial<AppSettings>): void
  setCapabilities(capabilities: RuntimeCapabilities): void
  setBuildConfig(config: RuntimeConfig): void
  setProfile(patch: Partial<Pick<AppState,
    | 'balance'
    | 'commissionBalance'
    | 'currencySymbol'
    | 'currencyUnit'
    | 'paymentEnabled'
    | 'inviteForce'
    | 'inviteCommissionRate'
    | 'inviteCommissionBalance'
  >>): void
  setAdmobConfig(patch: Partial<Pick<AppState,
    | 'admobCloudEnabled'
    | 'planRewardAdEnabled'
    | 'pointsRewardAdEnabled'
    | 'appOpenAdEnabled'
    | 'planRewardedAdUnitId'
    | 'planRewardSsvUserId'
    | 'planRewardSsvCustomData'
    | 'pointsRewardedAdUnitId'
    | 'pointsRewardSsvUserId'
    | 'pointsRewardSsvCustomData'
    | 'appOpenAdUnitId'
    | 'githubProjectUrl'
  >>): void
  setAuthConfig(patch: Partial<Pick<AppState,
    | 'oauthProviders'
    | 'inviteForce'
    | 'registerEmailVerifyEnabled'
    | 'registerCaptchaEnabled'
  >>): void
  setRewardLogs(logs: AdRewardLogItem[]): void
  setPlans(plans: PlanItem[]): void
  setInvites(invites: InviteItem[]): void
  setNotices(notices: NoticeItem[]): void
  setSubscriptionState(state: SubscriptionState): void
  reset(): void
}

const EMPTY_SUBSCRIPTION: SubscriptionState = {
  summary: '',
  blockReason: '',
  trafficUsedBytes: 0,
  trafficTotalBytes: 0,
  planName: '',
  expiredAt: 0,
}

type StatePatch = Partial<AppState> | ((state: AppState) => Partial<AppState>)

let state: AppState
const listeners = new Set<(state: AppState) => void>()

function set(patch: StatePatch): void {
  const next = typeof patch === 'function' ? patch(state) : patch
  state = { ...state, ...next }
  listeners.forEach((listener) => listener(state))
}

const initialState: AppState = {
  baseUrl: '',
  authData: '',
  email: '',
  subscribeUrl: '',
  nodes: [],
  vpn: null,
  preferredNodeIndex: 0,
  systemProxyActive: false,
  settings: {
    autoApplyProxy: true,
    autostart: false,
    silentStart: false,
    routingMode: 'rule',
    tunEnabled: true,
    systemProxyEnabled: false,
    nodeDns: DEFAULT_NODE_DNS,
    overseasDns: DEFAULT_OVERSEAS_DNS,
    directDns: DEFAULT_DIRECT_DNS,
    nodeTestTarget: DEFAULT_NODE_TEST_TARGET,
    vpnDnsMode: 'over_tcp',
    virtualDnsPool: DEFAULT_VIRTUAL_DNS_POOL,
    vpnIpv6Enabled: true,
    appRuleMode: 'exclude',
    excludedApps: '',
    allowedApps: '',
    themeMode: 'system',
    appLanguage: 'system',
  },
  capabilities: null,
  buildConfig: null,
  balance: 0,
  commissionBalance: 0,
  currencySymbol: '',
  currencyUnit: '',
  paymentEnabled: true,
  admobCloudEnabled: false,
  planRewardAdEnabled: false,
  pointsRewardAdEnabled: false,
  appOpenAdEnabled: false,
  planRewardedAdUnitId: '',
  planRewardSsvUserId: '',
  planRewardSsvCustomData: '',
  pointsRewardedAdUnitId: '',
  pointsRewardSsvUserId: '',
  pointsRewardSsvCustomData: '',
  appOpenAdUnitId: '',
  githubProjectUrl: '',
  inviteForce: false,
  inviteCommissionRate: 0,
  inviteCommissionBalance: 0,
  oauthProviders: [],
  registerEmailVerifyEnabled: false,
  registerCaptchaEnabled: false,
  adRewardLogs: [],
  plans: [],
  invites: [],
  notices: [],
  subscription: EMPTY_SUBSCRIPTION,
  setSession: (s) => set({ baseUrl: s.baseUrl, authData: s.authData, email: s.email }),
  setSubscribe: (s) => set({ subscribeUrl: s.subscribeUrl, nodes: s.nodes }),
  setNodeResult: (index, result) =>
    set((state) => {
      const nodes = state.nodes.slice()
      if (nodes[index]) nodes[index] = { ...nodes[index], ...result, _testing: false }
      return { nodes }
    }),
  setNodeLoading: (index) =>
    set((state) => {
      const nodes = state.nodes.slice()
      if (nodes[index]) nodes[index] = { ...nodes[index], _testing: true, latencyMs: undefined, testError: undefined }
      return { nodes }
    }),
  setVpn: (session) => set({ vpn: session }),
  setPreferredNodeIndex: (preferredNodeIndex) => set({ preferredNodeIndex }),
  setSystemProxyActive: (systemProxyActive) => set({ systemProxyActive }),
  updateVpnTraffic: (sessionId, uploadBytes, downloadBytes) =>
    set((state) =>
      state.vpn && state.vpn.sessionId === sessionId
        ? { vpn: { ...state.vpn, uploadBytes, downloadBytes } }
        : {},
    ),
  setSettings: (patch) => set((state) => ({ settings: { ...state.settings, ...patch } })),
  setCapabilities: (capabilities) => set({ capabilities }),
  setBuildConfig: (config) => set({ buildConfig: config, baseUrl: config.default_api_url }),
  setProfile: (patch) => set((state) => ({ ...state, ...patch })),
  setAdmobConfig: (patch) => set((state) => ({ ...state, ...patch })),
  setAuthConfig: (patch) => set((state) => ({ ...state, ...patch })),
  setRewardLogs: (adRewardLogs) => set({ adRewardLogs }),
  setPlans: (plans) => set({ plans }),
  setInvites: (invites) => set({ invites }),
  setNotices: (notices) => set({ notices }),
  setSubscriptionState: (subscription) => set({ subscription }),
  reset: () =>
    set({
      baseUrl: '',
      authData: '',
      email: '',
      subscribeUrl: '',
      nodes: [],
      vpn: null,
      preferredNodeIndex: 0,
      systemProxyActive: false,
      balance: 0,
      commissionBalance: 0,
      currencySymbol: '',
      currencyUnit: '',
      paymentEnabled: true,
      admobCloudEnabled: false,
      planRewardAdEnabled: false,
      pointsRewardAdEnabled: false,
      appOpenAdEnabled: false,
      planRewardedAdUnitId: '',
      planRewardSsvUserId: '',
      planRewardSsvCustomData: '',
      pointsRewardedAdUnitId: '',
      pointsRewardSsvUserId: '',
      pointsRewardSsvCustomData: '',
      appOpenAdUnitId: '',
      githubProjectUrl: '',
      inviteForce: false,
      inviteCommissionRate: 0,
      inviteCommissionBalance: 0,
      oauthProviders: [],
      registerEmailVerifyEnabled: false,
      registerCaptchaEnabled: false,
      adRewardLogs: [],
      plans: [],
      invites: [],
      notices: [],
      subscription: EMPTY_SUBSCRIPTION,
    }),
}

state = initialState

export const useAppStore = {
  getState: () => state,
  subscribe: (listener: (state: AppState) => void) => {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}
