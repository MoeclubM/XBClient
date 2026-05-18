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
}

export interface AppSettings {
  autoApplyProxy: boolean
  autostart: boolean
  nodeDns: string
  nodeTestTarget: string
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
  setSession(s: { baseUrl: string; authData: string; email: string }): void
  setSubscribe(s: { subscribeUrl: string; nodes: AppNode[] }): void
  setNodeResult(index: number, result: { latencyMs?: number; testError?: string }): void
  setVpn(session: VpnSession | null): void
  setSettings(patch: Partial<AppSettings>): void
  setCapabilities(capabilities: RuntimeCapabilities): void
  reset(): void
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
  },
  capabilities: null,
  setSession: (s) => set({ baseUrl: s.baseUrl, authData: s.authData, email: s.email }),
  setSubscribe: (s) => set({ subscribeUrl: s.subscribeUrl, nodes: s.nodes }),
  setNodeResult: (index, result) =>
    set((state) => {
      const nodes = state.nodes.slice()
      if (nodes[index]) nodes[index] = { ...nodes[index], ...result }
      return { nodes }
    }),
  setVpn: (session) => set({ vpn: session }),
  setSettings: (patch) => set((state) => ({ settings: { ...state.settings, ...patch } })),
  setCapabilities: (capabilities) => set({ capabilities }),
  reset: () =>
    set({
      baseUrl: '',
      authData: '',
      email: '',
      subscribeUrl: '',
      nodes: [],
      vpn: null,
    }),
}))
