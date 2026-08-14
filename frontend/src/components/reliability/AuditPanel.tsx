import { Button, Flex, Space, Table, Tag, Tooltip, Typography, type TableColumnsType } from 'antd';
import { CheckCircleOutlined, ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchAuditLogs, verifyAuditChain } from '../../api/admin';
import type { AuditLog } from '../../types';

export default function AuditPanel() {
  const logsQuery = useQuery({
    queryKey: ['audit-logs'], queryFn: () => fetchAuditLogs(300), refetchInterval: 10_000,
  });
  const verifyQuery = useQuery({
    queryKey: ['audit-verify'], queryFn: verifyAuditChain, refetchInterval: 30_000,
  });
  const verification = verifyQuery.data;
  const columns: TableColumnsType<AuditLog> = [
    { title: 'Time', dataIndex: 'operationTime', width: 170, render: (value) => new Date(value).toLocaleString() },
    { title: 'Operator', dataIndex: 'operator', width: 110 },
    { title: 'Operation', dataIndex: 'operationType', width: 190 },
    { title: 'Resource', width: 210, render: (_, row) => `${row.resourceType ?? '-'} · ${row.resourceId ?? '-'}` },
    { title: 'Outcome', dataIndex: 'outcome', width: 100, render: (value) => <Tag color={value === 'SUCCESS' ? 'success' : 'error'}>{value}</Tag> },
    { title: 'Latency', dataIndex: 'latencyMs', width: 90, render: (value) => value == null ? '-' : `${value} ms` },
    {
      title: 'Request', dataIndex: 'requestId', width: 150, ellipsis: true,
      render: (value) => value ? <Typography.Text code copyable>{value.slice(0, 12)}</Typography.Text> : '-',
    },
    {
      title: 'Hash', dataIndex: 'entryHash', width: 150,
      render: (value) => value ? (
        <Tooltip title={value}><Typography.Text code copyable>{value.slice(0, 12)}</Typography.Text></Tooltip>
      ) : '-',
    },
  ];

  return (
    <div>
      <Flex justify="space-between" align="center" gap={12} wrap style={{ marginBottom: 16 }}>
        <Space>
          {verification?.valid ? <CheckCircleOutlined style={{ color: '#237804', fontSize: 20 }} /> : <WarningOutlined style={{ color: '#a8071a', fontSize: 20 }} />}
          <div>
            <Typography.Text strong>{verification?.valid ? 'Audit chain verified' : 'Audit chain verification failed'}</Typography.Text>
            <br />
            <Typography.Text type="secondary">
              {verification ? `${verification.verifiedEntries} entries · ${verification.message}` : 'Verifying'}
            </Typography.Text>
          </div>
        </Space>
        <Button icon={<ReloadOutlined />} loading={verifyQuery.isFetching} onClick={() => verifyQuery.refetch()}>
          Verify chain
        </Button>
      </Flex>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={logsQuery.data ?? []}
        loading={logsQuery.isLoading}
        size="small"
        scroll={{ x: 1250 }}
        pagination={{ pageSize: 25 }}
        expandable={{ expandedRowRender: (row) => <Typography.Text code>{row.detail ?? '{}'}</Typography.Text> }}
      />
    </div>
  );
}
