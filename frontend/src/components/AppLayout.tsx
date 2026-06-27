import { useState } from 'react';
import { Layout, Menu, Typography, Badge, Space, theme } from 'antd';
import {
  DashboardOutlined,
  ThunderboltOutlined,
  ExperimentOutlined,
  SafetyOutlined,
  AlertOutlined,
  BarChartOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  LineChartOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchHealth } from '../api/admin';

const { Header, Sider, Content } = Layout;

const menuItems = [
  { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/strategies', icon: <ThunderboltOutlined />, label: 'Strategies' },
  { key: '/factors', icon: <ExperimentOutlined />, label: 'Factors' },
  { key: '/risk', icon: <SafetyOutlined />, label: 'Risk' },
  { key: '/signals', icon: <AlertOutlined />, label: 'Signals' },
  { key: '/backtests', icon: <BarChartOutlined />, label: 'Backtests' },
  { key: '/backtest-results', icon: <LineChartOutlined />, label: 'Backtest Results' },
];

export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { token } = theme.useToken();

  const { data: health } = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
    refetchInterval: 5000,
  });

  const isUp = health?.status === 'UP';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        theme="dark"
        width={200}
        style={{ overflow: 'auto', height: '100vh', position: 'fixed', left: 0, top: 0, bottom: 0 }}
      >
        <div
          style={{
            height: 48,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderBottom: '1px solid rgba(255,255,255,0.1)',
          }}
        >
          <Typography.Text strong style={{ color: '#fff', fontSize: collapsed ? 14 : 16 }}>
            {collapsed ? 'CT' : 'Crypto Trading'}
          </Typography.Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>

      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s' }}>
        <Header
          style={{
            padding: '0 24px',
            background: token.colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
            position: 'sticky',
            top: 0,
            zIndex: 10,
          }}
        >
          <Space>
            {collapsed ? (
              <MenuUnfoldOutlined style={{ fontSize: 18, cursor: 'pointer' }} onClick={() => setCollapsed(false)} />
            ) : (
              <MenuFoldOutlined style={{ fontSize: 18, cursor: 'pointer' }} onClick={() => setCollapsed(true)} />
            )}
          </Space>

          <Space size="middle">
            <Badge status={isUp ? 'success' : 'error'} text={isUp ? 'System UP' : 'System DOWN'} />
            {health && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {health.strategyCount} strategies / {health.factorCount} factors / {health.totalSignalCount} signals
              </Typography.Text>
            )}
          </Space>
        </Header>

        <Content style={{ margin: 16, padding: 24, background: token.colorBgContainer, borderRadius: token.borderRadiusLG, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
