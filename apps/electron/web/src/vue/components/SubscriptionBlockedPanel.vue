<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { isPxBoard } from '../../api/pxboard'
import { appState, t } from '../state'
import { useSubscriptionBlock } from '../composables/useSubscriptionBlock'

defineProps<{ showSummary?: boolean }>()

const router = useRouter()
const { blockTitle, blockDescription } = useSubscriptionBlock()
const targetRoute = computed(() =>
  isPxBoard() && !appState.panelExtensions.includes('commerce') ? '/profile' : '/plans',
)
const actionLabel = computed(() =>
  isPxBoard() && !appState.panelExtensions.includes('commerce') ? t('nav_profile') : t('go_to_plans'),
)
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
        <v-btn class="mt-4" color="primary" block @click="router.push(targetRoute)">
          {{ actionLabel }}
        </v-btn>
      </v-card-text>
    </v-card>
  </div>
</template>
