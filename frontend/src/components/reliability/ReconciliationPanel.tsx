import { Button, Flex, Input, Modal, Select, Space, Table, Tag, Typography, type TableColumnsType } from 'antd';
import { AuditOutlined, CheckOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import {
  fetchPaperAccounts,
  fetchReconciliationIncidents,
  resolveReconciliationIncident,
  runReconciliation,
} from '../../api/admin';
import { notify } from '../../feedback/notify';
import type { ReconciliationIncident } from '../../types';

export default function ReconciliationPanel() {
  const queryClient = useQueryClient();
  const [accountId, setAccountId] = useState<string>();
  const [incidentId, setIncidentId] = useState<string>();
  const [resolution, setResolution] = useState('Reviewed and accepted');
  const accountsQuery = useQuery({ queryKey: ['paper-accounts'], queryFn: () => fetchPaperAccounts(100) });
  const activeAccount = accountId ?? accountsQuery.data?.[0]?.accountId;
  const incidentsQuery = useQuery({
    queryKey: ['reconciliation-incidents', activeAccount],
    queryFn: () => fetchReconciliationIncidents(activeAccount, undefined, 500),
    enabled: Boolean(activeAccount),
    refetchInterval: 10_000,
  });
  const runMutation = useMutation({
    mutationFn: () => runReconciliation(activeAccount!),
    onSuccess: (report) => {
      notify[report.openIncidents === 0 ? 'success' : 'warning'](
        report.openIncidents === 0 ? 'Reconciliation passed' : `${report.openIncidents} incidents remain open`,
      );
      void queryClient.invalidateQueries({ queryKey: ['reconciliation-incidents'] });
    },
  });
  const resolveMutation = useMutation({
    mutationFn: () => resolveReconciliationIncident(incidentId!, resolution),
    onSuccess: () => {
      setIncidentId(undefined);
      notify.success('Incident resolved');
      void queryClient.invalidateQueries({ queryKey: ['reconciliation-incidents'] });
    },
  });
  const columns: TableColumnsType<ReconciliationIncident> = [
    { title: 'Detected', dataIndex: 'detectedAtMs', width: 170, render: (value) => new Date(value).toLocaleString() },
    { title: 'Severity', dataIndex: 'severity', width: 100, render: (value) => <Tag color={value === 'CRITICAL' ? 'error' : 'warning'}>{value}</Tag> },
    { title: 'Type', dataIndex: 'incidentType', width: 220 },
    { title: 'Aggregate', width: 230, render: (_, row) => `${row.aggregateType} · ${row.aggregateId}` },
    { title: 'Status', dataIndex: 'status', width: 100, render: (value) => <Tag>{value}</Tag> },
    {
      title: '', width: 70, fixed: 'right',
      render: (_, row) => row.status === 'OPEN' ? (
        <Button type="text" icon={<CheckOutlined />} title="Resolve incident" onClick={() => setIncidentId(row.incidentId)} />
      ) : null,
    },
  ];

  return (
    <div>
      <Flex justify="space-between" gap={12} wrap style={{ marginBottom: 16 }}>
        <Select
          value={activeAccount}
          placeholder="Paper account"
          style={{ width: 280 }}
          options={(accountsQuery.data ?? []).map((account) => ({ value: account.accountId, label: `${account.accountName} · ${account.status}` }))}
          onChange={setAccountId}
        />
        <Button
          type="primary"
          icon={<AuditOutlined />}
          disabled={!activeAccount}
          loading={runMutation.isPending}
          onClick={() => runMutation.mutate()}
        >
          Run reconciliation
        </Button>
      </Flex>
      {runMutation.data && (
        <Space style={{ marginBottom: 14 }}>
          <Tag color={runMutation.data.openIncidents === 0 ? 'success' : 'error'}>
            {runMutation.data.openIncidents} open incidents
          </Tag>
          <Typography.Text type="secondary">
            {runMutation.data.ordersChecked} orders · {runMutation.data.balancesChecked} balances · {runMutation.data.positionsChecked} positions
          </Typography.Text>
        </Space>
      )}
      <Table
        rowKey="incidentId"
        columns={columns}
        dataSource={incidentsQuery.data ?? []}
        loading={incidentsQuery.isLoading}
        size="small"
        scroll={{ x: 1000 }}
        pagination={{ pageSize: 25 }}
        expandable={{ expandedRowRender: (row) => <pre style={{ whiteSpace: 'pre-wrap' }}>{`Expected: ${row.expectedJson}\nActual: ${row.actualJson}`}</pre> }}
      />
      <Modal
        title="Resolve reconciliation incident"
        open={Boolean(incidentId)}
        okText="Resolve"
        confirmLoading={resolveMutation.isPending}
        onOk={() => resolveMutation.mutate()}
        onCancel={() => setIncidentId(undefined)}
      >
        <Input.TextArea value={resolution} rows={4} maxLength={500} onChange={(event) => setResolution(event.target.value)} />
      </Modal>
    </div>
  );
}
