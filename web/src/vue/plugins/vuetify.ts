import 'vuetify/styles'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

export const vuetify = createVuetify({
  components,
  directives,
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        dark: false,
        colors: {
          primary: '#0B57D0',
          secondary: '#D3E3FD',
          background: '#F6F8FC',
          surface: 'rgba(255,255,255,0.72)',
          'surface-bright': '#FFFFFF',
          'surface-variant': '#E2E8F2',
          'on-surface': '#161B22',
          'on-surface-variant': '#4C5668',
          outline: '#9AA7BA',
        },
      },
      dark: {
        dark: true,
        colors: {
          primary: '#9CC2FF',
          secondary: '#123A6F',
          background: '#0F141B',
          surface: 'rgba(27,34,45,0.72)',
          'surface-bright': '#252D39',
          'surface-variant': '#343D4C',
          'on-surface': '#E5E9F0',
          'on-surface-variant': '#C2CAD8',
          outline: '#7F8A9B',
        },
      },
    },
  },
  defaults: {
    VBtn: {
      rounded: 'xl',
      elevation: 0,
    },
    VCard: {
      rounded: 'xl',
      elevation: 0,
    },
    VTextField: {
      variant: 'outlined',
      density: 'comfortable',
      hideDetails: 'auto',
    },
    VSelect: {
      variant: 'outlined',
      density: 'comfortable',
      hideDetails: 'auto',
    },
  },
})
