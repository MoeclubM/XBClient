import { app, dialog } from 'electron'
import electronUpdater from 'electron-updater'

const { autoUpdater } = electronUpdater

export function setupAutoUpdater(beforeInstall) {
  if (!app.isPackaged || process.platform !== 'win32') return

  autoUpdater.autoDownload = true
  autoUpdater.autoInstallOnAppQuit = true

  autoUpdater.on('error', (err) => {
    console.error('[updater]', err)
  })

  autoUpdater.on('update-downloaded', () => {
    dialog
      .showMessageBox({
        type: 'info',
        title: '发现新版本',
        message: '新版本已下载，重启后即可完成安装。',
        buttons: ['立即重启', '稍后'],
        defaultId: 0,
        cancelId: 1,
      })
      .then(async ({ response }) => {
        if (response !== 0) return
        await beforeInstall()
        autoUpdater.quitAndInstall()
      })
      .catch((error) => {
        dialog.showErrorBox('更新失败', error.message)
      })
  })

  setTimeout(() => {
    void autoUpdater.checkForUpdatesAndNotify().catch((error) => console.error('[updater]', error))
  }, 5000)
}
