import { useState, Component, type ReactNode, type ErrorInfo } from 'react';
import { Layout, Menu, Typography, Badge, Space, Button, theme, Result } from 'antd';
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
  LogoutOutlined,
  SettingOutlined,
  OrderedListOutlined,
  HistoryOutlined,
  MonitorOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchHealth } from '../api/admin';
import { useAuth } from '../auth/useAuth';

const { Header, Sider, Content } = Layout;

// ── 错误边界 ───────────────────────────────────────────────────
interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends Component<{ children: ReactNode }, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false, error: null };

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        <Result
          status="error"
          title="页面出错"
          subTitle={this.state.error?.message}
          extra={
            <Button type="primary" onClick={() => this.setState({ hasError: false, error: null })}>
              重试
            </Button>
          }
        />
      );
    }
    return this.props.children;
  }
}

// ── 菜单配置 ───────────────────────────────────────────────────
const menuItems = [
  { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/strategies', icon: <ThunderboltOutlined />, label: 'Strategies' },
  { key: '/factors', icon: <ExperimentOutlined />, label: 'Factors' },
  { key: '/risk', icon: <SafetyOutlined />, label: 'Risk' },
  { key: '/signals', icon: <AlertOutlined />, label: 'Signals' },
  { key: '/orders', icon: <OrderedListOutlined />, label: 'Orders' },
  { key: '/backtests', icon: <BarChartOutlined />, label: 'Backtests' },
  { key: '/backtest-results', icon: <LineChartOutlined />, label: 'Backtest Results' },
  { key: '/backtest-jobs', icon: <HistoryOutlined />, label: 'Backtest Jobs' },
  { key: '/reliability', icon: <MonitorOutlined />, label: 'Reliability' },
  { key: '/configs', icon: <SettingOutlined />, label: 'Configs' },
];

// ── AppLayout ──────────────────────────────────────────────────
export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { token: antToken } = theme.useToken();
  const { logout } = useAuth();

  const { data: health } = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
    refetchInterval: 5000,
  });

  const isUp = health?.status === 'UP';

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

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
            background: antToken.colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: `1px solid ${antToken.colorBorderSecondary}`,
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
            <Button
              type="text"
              icon={<LogoutOutlined />}
              onClick={handleLogout}
              size="small"
            >
              {collapsed ? '' : '退出'}
            </Button>
          </Space>
        </Header>

        <Content style={{ margin: 16, padding: 24, background: antToken.colorBgContainer, borderRadius: antToken.borderRadiusLG, overflow: 'auto' }}>
          <ErrorBoundary>
            <Outlet />
          </ErrorBoundary>
        </Content>
      </Layout>
    </Layout>
  );
}
