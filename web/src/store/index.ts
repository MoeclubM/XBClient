import { create } from 'zustand'
import type { RuntimeCapabilities } from '../api/system'
import { DEFAULT_NODE_DNS, DEFAULT_NODE_TEST_TARGET } from '../nodes'

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
}

export interface VpnSession {
  sessionId: number
  socksAddr: string
  nodeIndex: number
  uploadBytes: number
  downloadBytes: number
}

export interface AppSettings {
  autoApplyProxy: boolean
  autostart: boolean
  nodeDns: string
  nodeTestTarget: string
  apiUserAgent: string
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
  settings: AppSettings
  capabilities: RuntimeCapabilities | null
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
  setVpn(session: VpnSession | null): void
  updateVpnTraffic(sessionId: number, uploadBytes: number, downloadBytes: number): void
  setSettings(patch: Partial<AppSettings>): void
  setCapabilities(capabilities: RuntimeCapabilities): void
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

export const useAppStore = create<AppState>((set) => ({
  baseUrl: '',
  authData: '',
  email: '',
  subscribeUrl: '',
  nodes: [],
  vpn: null,
  settings: {
    autoApplyProxy: true,
    autostart: false,
    nodeDns: DEFAULT_NODE_DNS,
    nodeTestTarget: DEFAULT_NODE_TEST_TARGET,
    apiUserAgent: '',
    themeMode: 'system',
    appLanguage: 'system',
  },
  capabilities: null,
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
      if (nodes[index]) nodes[index] = { ...nodes[index], ...result }
      return { nodes }
    }),
  setVpn: (session) => set({ vpn: session }),
  updateVpnTraffic: (sessionId, uploadBytes, downloadBytes) =>
    set((state) =>
      state.vpn && state.vpn.sessionId === sessionId
        ? { vpn: { ...state.vpn, uploadBytes, downloadBytes } }
        : {},
    ),
  setSettings: (patch) => set((state) => ({ settings: { ...state.settings, ...patch } })),
  setCapabilities: (capabilities) => set({ capabilities }),
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
}))
