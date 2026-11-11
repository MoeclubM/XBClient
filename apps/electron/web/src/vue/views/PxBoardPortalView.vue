<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PxBoardAccountPanel from '../components/pxboard/PxBoardAccountPanel.vue'
import PxBoardAffiliatePanel from '../components/pxboard/PxBoardAffiliatePanel.vue'
import PxBoardPlansPanel from '../components/pxboard/PxBoardPlansPanel.vue'
import PxBoardTicketsPanel from '../components/pxboard/PxBoardTicketsPanel.vue'

const route = useRoute()
const activePanel = computed(() => {
  if (route.path === '/plans') return PxBoardPlansPanel
  if (route.path === '/tickets') return PxBoardTicketsPanel
  if (route.path === '/promotion') return PxBoardAffiliatePanel
  return PxBoardAccountPanel
})
</script>

<template>
  <component :is="activePanel" />
</template>
