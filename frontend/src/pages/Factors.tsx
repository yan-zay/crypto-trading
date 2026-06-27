import { Card, Table, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { fetchFactors } from '../api/admin';

export default function Factors() {
  const { data, isLoading } = useQuery({
    queryKey: ['factors'],
    queryFn: fetchFactors,
  });

  const columns = [
    {
      title: '#',
      key: 'index',
      width: 60,
      render: (_: unknown, __: unknown, index: number) => index + 1,
    },
    {
      title: 'Factor Name',
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
    },
  ];

  return (
    <div>
      <Typography.Title level={4} style={{ marginBottom: 24 }}>
        Factors
      </Typography.Title>
      <Card size="small">
        <Table
          dataSource={data ?? []}
          columns={columns}
          rowKey="name"
          loading={isLoading}
          size="small"
          pagination={false}
        />
      </Card>
    </div>
  );
}
