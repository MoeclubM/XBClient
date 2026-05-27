<script setup lang="ts">
import { useRouter } from 'vue-router'
import { appState, t } from '../state'
import { useSubscriptionBlock } from '../composables/useSubscriptionBlock'

defineProps<{ showSummary?: boolean }>()

const router = useRouter()
const { blockTitle, blockDescription } = useSubscriptionBlock()
</script>

<template>
  <div v-if="appState.subscription.blockReason" class="page-section">
    <p class="section-label">{{ blockTitle }}</p>
    <v-card class="panel-card">
      <v-card-text>
        <p class="muted">{{ blockDescription }}</p>
        <p v-if="showSummary && appState.subscription.summary" class="text-body-1 font-weight-bold mt-2">
          {{ appState.subscription.summary }}
        </p>
        <v-btn class="mt-4" color="primary" block @click="router.push('/plans')">
          {{ t('go_to_plans') }}
        </v-btn>
      </v-card-text>
    </v-card>
  </div>
</template>
