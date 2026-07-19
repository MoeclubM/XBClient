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
          primary: '#0b57d0',
          'on-primary': '#ffffff',
          'primary-container': '#d3e3fd',
          'on-primary-container': '#041e49',
          secondary: '#42526e',
          'on-secondary': '#ffffff',
          'secondary-container': '#d9e2f6',
          'on-secondary-container': '#101c2f',
          tertiary: '#006b5f',
          background: '#f6f8fc',
          'on-background': '#161b22',
          surface: '#ffffff',
          'on-surface': '#161b22',
          'surface-container-low': '#ffffff',
          'surface-container': '#ffffff',
          'surface-container-high': '#eff3fa',
          'surface-variant': '#e2e8f2',
          'on-surface-variant': '#4c5668',
          outline: '#9aa7ba',
          'outline-variant': '#d7dee9',
          error: '#ef4444',
        },
      },
      dark: {
        dark: true,
        colors: {
          primary: '#9cc2ff',
          'on-primary': '#073a8c',
          'primary-container': '#123a6f',
          'on-primary-container': '#d8e7ff',
          secondary: '#bbc6dc',
          'on-secondary': '#273143',
          'secondary-container': '#323d52',
          'on-secondary-container': '#dde6f8',
          tertiary: '#68dbcd',
          background: '#0f141b',
          'on-background': '#e5e9f0',
          surface: '#1b222d',
          'on-surface': '#e5e9f0',
          'surface-container-low': '#171c24',
          'surface-container': '#1b222d',
          'surface-container-high': '#252d39',
          'surface-variant': '#343d4c',
          'on-surface-variant': '#c2cad8',
          outline: '#7f8a9b',
          'outline-variant': '#303948',
          error: '#ef4444',
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
