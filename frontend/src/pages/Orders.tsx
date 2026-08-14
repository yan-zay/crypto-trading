import { Button, Flex, Row, Col, Space, Tabs, Tag, Typography } from 'antd';
import { AuditOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import {
  cancelPaperOrder,
  fetchOrder,
  fetchPaperAccounts,
  fetchPaperAttribution,
  fetchPaperEquity,
  fetchPaperExecutionQuality,
  fetchPaperFills,
  fetchPaperLedger,
  fetchPaperMarks,
  fetchPaperOrders,
  fetchPaperTrades,
  fetchPaperTradingStatus,
  resumePaperTrading,
  runReconciliation,
  startPaperTrading,
  stopPaperTrading,
} from '../api/admin';
import OrderDetailDrawer from '../components/trading/OrderDetailDrawer';
import PaperAccountBar from '../components/trading/PaperAccountBar';
import PaperAnalytics from '../components/trading/PaperAnalytics';
import PaperExecutions from '../components/trading/PaperExecutions';
import PaperOrdersTable from '../components/trading/PaperOrdersTable';
import PaperOrderTicket from '../components/trading/PaperOrderTicket';
import PaperPortfolio from '../components/trading/PaperPortfolio';
import { notify } from '../feedback/notify';

export default function Orders() {
  const queryClient = useQueryClient();
  const [selectedAccountId, setSelectedAccountId] = useState<string>();
  const [selectedOrderId, setSelectedOrderId] = useState<string>();

  const accountsQuery = useQuery({
    queryKey: ['paper-accounts'], queryFn: () => fetchPaperAccounts(100), refetchInterval: 10_000,
  });
  const statusQuery = useQuery({
    queryKey: ['paper-status', selectedAccountId],
    queryFn: () => fetchPaperTradingStatus(selectedAccountId),
    refetchInterval: 3000,
  });
  const accountId = selectedAccountId
    ?? statusQuery.data?.accountId
    ?? accountsQuery.data?.[0]?.accountId;
  const marksQuery = useQuery({ queryKey: ['paper-marks'], queryFn: fetchPaperMarks, refetchInterval: 5000 });
  const ordersQuery = useQuery({
    queryKey: ['paper-orders', accountId], queryFn: () => fetchPaperOrders(accountId), enabled: Boolean(accountId), refetchInterval: 3000,
  });
  const fillsQuery = useQuery({
    queryKey: ['paper-fills', accountId], queryFn: () => fetchPaperFills(accountId), enabled: Boolean(accountId), refetchInterval: 5000,
  });
  const tradesQuery = useQuery({
    queryKey: ['paper-trades', accountId], queryFn: () => fetchPaperTrades(accountId), enabled: Boolean(accountId), refetchInterval: 5000,
  });
  const equityQuery = useQuery({
    queryKey: ['paper-equity', accountId], queryFn: () => fetchPaperEquity(accountId), enabled: Boolean(accountId), refetchInterval: 5000,
  });
  const ledgerQuery = useQuery({
    queryKey: ['paper-ledger', accountId], queryFn: () => fetchPaperLedger(accountId), enabled: Boolean(accountId), refetchInterval: 5000,
  });
  const attributionQuery = useQuery({
    queryKey: ['paper-attribution', accountId], queryFn: () => fetchPaperAttribution(accountId), enabled: Boolean(accountId), refetchInterval: 5000,
  });
  const executionQuery = useQuery({
    queryKey: ['paper-execution-quality', accountId], queryFn: () => fetchPaperExecutionQuality(accountId), enabled: Boolean(accountId), refetchInterval: 5000,
  });
  const detailQuery = useQuery({
    queryKey: ['oms-order', selectedOrderId], queryFn: () => fetchOrder(selectedOrderId!), enabled: Boolean(selectedOrderId),
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ predicate: (query) => String(query.queryKey[0]).startsWith('paper-') });
    void queryClient.invalidateQueries({ queryKey: ['oms-order', selectedOrderId] });
  };
  const startMutation = useMutation({
    mutationFn: ({ balance, name }: { balance: number; name?: string }) => startPaperTrading(balance, name),
    onSuccess: (status) => {
      if (status.accountId) setSelectedAccountId(status.accountId);
      notify.success('Paper account started');
      refresh();
    },
  });
  const stopMutation = useMutation({
    mutationFn: () => stopPaperTrading(accountId),
    onSuccess: () => { notify.success('Paper account stopped'); refresh(); },
  });
  const resumeMutation = useMutation({
    mutationFn: () => resumePaperTrading(accountId!),
    onSuccess: () => { notify.success('Paper account resumed'); refresh(); },
  });
  const cancelMutation = useMutation({
    mutationFn: (orderId: string) => cancelPaperOrder(orderId, accountId),
    onSuccess: () => { notify.success('Order cancelled'); refresh(); },
  });
  const reconciliationMutation = useMutation({
    mutationFn: () => runReconciliation(accountId!),
    onSuccess: (report) => {
      if (report.openIncidents === 0) notify.success('Reconciliation passed');
      else notify.warning(`Reconciliation found ${report.openIncidents} open incidents`);
    },
  });

  const status = statusQuery.data;
  const loading = ordersQuery.isLoading || fillsQuery.isLoading || tradesQuery.isLoading;

  return (
    <div>
      <Flex justify="space-between" align="center" gap={12} wrap style={{ marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>Trading Operations</Typography.Title>
        <Space>
          {reconciliationMutation.data && (
            <Tag color={reconciliationMutation.data.openIncidents === 0 ? 'success' : 'error'}>
              Reconciliation: {reconciliationMutation.data.openIncidents} open
            </Tag>
          )}
          <Button
            icon={<AuditOutlined />}
            disabled={!accountId}
            loading={reconciliationMutation.isPending}
            onClick={() => reconciliationMutation.mutate()}
          >
            Reconcile
          </Button>
          <Button icon={<ReloadOutlined />} loading={statusQuery.isFetching} onClick={refresh} />
        </Space>
      </Flex>

      <PaperAccountBar
        accounts={accountsQuery.data ?? []}
        accountId={accountId}
        status={status}
        busy={startMutation.isPending || stopMutation.isPending || resumeMutation.isPending}
        onSelect={setSelectedAccountId}
        onStart={(balance, name) => startMutation.mutate({ balance, name })}
        onStop={() => stopMutation.mutate()}
        onResume={() => resumeMutation.mutate()}
      />

      <Tabs
        style={{ marginTop: 12 }}
        items={[
          {
            key: 'workspace',
            label: 'Workspace',
            children: (
              <Row gutter={[24, 24]}>
                <Col xs={24} xl={8}>
                  <PaperOrderTicket
                    accountId={accountId}
                    running={Boolean(status?.running)}
                    marks={marksQuery.data ?? []}
                    onChanged={refresh}
                  />
                </Col>
                <Col xs={24} xl={16}>
                  <PaperPortfolio
                    balances={status?.balances ?? []}
                    positions={status?.positions ?? []}
                    marks={marksQuery.data ?? []}
                    loading={statusQuery.isLoading}
                  />
                </Col>
              </Row>
            ),
          },
          {
            key: 'orders',
            label: `Orders (${ordersQuery.data?.length ?? 0})`,
            children: (
              <PaperOrdersTable
                orders={ordersQuery.data ?? []}
                loading={ordersQuery.isLoading}
                cancelling={cancelMutation.isPending}
                onInspect={setSelectedOrderId}
                onCancel={(orderId) => cancelMutation.mutate(orderId)}
              />
            ),
          },
          {
            key: 'executions',
            label: `Executions (${fillsQuery.data?.length ?? 0})`,
            children: <PaperExecutions fills={fillsQuery.data ?? []} trades={tradesQuery.data ?? []} loading={loading} />,
          },
          {
            key: 'analytics',
            label: 'Analytics & Ledger',
            children: (
              <PaperAnalytics
                equity={equityQuery.data ?? []}
                attribution={attributionQuery.data ?? {}}
                execution={executionQuery.data}
                ledger={ledgerQuery.data ?? []}
                loading={equityQuery.isLoading || attributionQuery.isLoading || ledgerQuery.isLoading}
              />
            ),
          },
        ]}
      />

      <OrderDetailDrawer
        open={Boolean(selectedOrderId)}
        loading={detailQuery.isLoading}
        detail={detailQuery.data}
        onClose={() => setSelectedOrderId(undefined)}
      />
    </div>
  );
}
