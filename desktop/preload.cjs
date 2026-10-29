const { contextBridge, ipcRenderer } = require('electron');
const request = (operation, key, value) => {
  const result = ipcRenderer.sendSync('provider-storage', operation, key, value);
  if (result.error) throw new Error(result.error);
  return result.value;
};
contextBridge.exposeInMainWorld('megastreamDesktop', {
  control: {
    state: () => ipcRenderer.invoke('desktop-control', 'state'),
    sync: () => ipcRenderer.invoke('desktop-control', 'sync'),
    activate: key => ipcRenderer.invoke('desktop-control', 'activate', key),
    requestCode: () => ipcRenderer.invoke('desktop-control', 'code'),
    subscribe: callback => {
      const listener = (_event, state) => callback(state);
      ipcRenderer.on('desktop-control-state', listener);
      return () => ipcRenderer.removeListener('desktop-control-state', listener);
    }
  },
  storage: {
    getItem: key => request('getItem', key),
    setItem: (key, value) => request('setItem', key, value),
    removeItem: key => request('removeItem', key)
  }
});
