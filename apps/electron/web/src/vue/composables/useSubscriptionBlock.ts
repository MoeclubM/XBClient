import { computed } from 'vue'
import { appState, t } from '../state'

export function useSubscriptionBlock() {
  const blockTitle = computed(() => {
    if (appState.subscription.blockReason === 'no_plan') return t('subscription_no_plan_title')
    if (appState.subscription.blockReason === 'traffic_exceeded') return t('subscription_traffic_exceeded_title')
    return t('subscription_expired_title')
  })

  const blockDescription = computed(() => {
    if (appState.subscription.blockReason === 'no_plan') return t('subscription_no_plan_body')
    if (appState.subscription.blockReason === 'traffic_exceeded') return t('subscription_traffic_exceeded_body')
    return t('subscription_expired_body')
  })

  return { blockTitle, blockDescription }
}
