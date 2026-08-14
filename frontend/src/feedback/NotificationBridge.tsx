import { useEffect } from 'react';
import { App } from 'antd';
import { setMessageApi } from './notify';

export function NotificationBridge() {
  const { message } = App.useApp();

  useEffect(() => {
    setMessageApi(message);
    return () => setMessageApi(null);
  }, [message]);

  return null;
}
