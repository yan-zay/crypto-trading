import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Card, Table, Button, Modal, Form, Input, Select, Tag, Space,
  Popconfirm, Typography, Drawer,
} from 'antd';
import {
  PlusOutlined, CheckCircleOutlined, SendOutlined,
  RollbackOutlined, HistoryOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  getConfigs,
  createConfigDraft,
  validateConfig,
  publishConfig,
  rollbackConfig,
  getConfigHistory,
} from '../api/admin';
import { notify } from '../feedback/notify';

const { TextArea } = Input;
const { Text } = Typography;

const CONFIG_TYPES = [
  { value: 'STRATEGY', label: '策略配置' },
  { value: 'RISK', label: '风控配置' },
  { value: 'FACTOR', label: '因子配置' },
  { value: 'EXECUTION', label: '执行配置' },
  { value: 'CONNECTOR', label: '连接器配置' },
];

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  draft: { color: 'default', text: '草稿' },
  validated: { color: 'processing', text: '已验证' },
  published: { color: 'blue', text: '已发布' },
  active: { color: 'success', text: '生效中' },
  rolled_back: { color: 'warning', text: '已回滚' },
  archived: { color: 'default', text: '已归档' },
};

export default function Configs() {
  const queryClient = useQueryClient();
  const [createForm] = Form.useForm();
  const [createOpen, setCreateOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [selectedType, setSelectedType] = useState<string>('STRATEGY');
  const [selectedKey, setSelectedKey] = useState<string>('');
  const [history, setHistory] = useState<any[]>([]);

  // 查询当前生效配置
  const { data: configs, isLoading } = useQuery({
    queryKey: ['configs', selectedType],
    queryFn: () => getConfigs(selectedType),
  });

  // 创建草稿
  const createMutation = useMutation({
    mutationFn: (values: { type: string; configKey: string; contentJson: string; remark: string }) =>
      createConfigDraft(values.type, values.configKey, values.contentJson, values.remark),
    onSuccess: () => {
      notify.success('草稿创建成功');
      setCreateOpen(false);
      createForm.resetFields();
      queryClient.invalidateQueries({ queryKey: ['configs'] });
    },
    onError: () => notify.error('创建失败'),
  });

  // 验证
  const validateMutation = useMutation({
    mutationFn: (versionId: string) => validateConfig(versionId),
    onSuccess: () => {
      notify.success('验证成功');
      queryClient.invalidateQueries({ queryKey: ['configs'] });
    },
    onError: () => notify.error('验证失败'),
  });

  // 发布
  const publishMutation = useMutation({
    mutationFn: (versionId: string) => publishConfig(versionId),
    onSuccess: () => {
      notify.success('发布成功');
      queryClient.invalidateQueries({ queryKey: ['configs'] });
    },
    onError: () => notify.error('发布失败'),
  });

  // 回滚
  const rollbackMutation = useMutation({
    mutationFn: ({ versionId, targetVersionId }: { versionId: string; targetVersionId: string }) =>
      rollbackConfig(versionId, targetVersionId),
    onSuccess: () => {
      notify.success('回滚成功');
      queryClient.invalidateQueries({ queryKey: ['configs'] });
    },
    onError: () => notify.error('回滚失败'),
  });

  // 查看历史
  const viewHistory = async (type: string, configKey: string) => {
    setSelectedKey(configKey);
    try {
      const data = await getConfigHistory(type, configKey);
      setHistory(data);
      setHistoryOpen(true);
    } catch {
      notify.error('获取历史失败');
    }
  };

  const columns = [
    {
      title: '配置键',
      dataIndex: 'configKey',
      key: 'configKey',
      render: (text: string) => <Text strong>{text}</Text>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const s = STATUS_MAP[status] || { color: 'default', text: status };
        return <Tag color={s.color}>{s.text}</Tag>;
      },
    },
    {
      title: '版本 ID',
      dataIndex: 'versionId',
      key: 'versionId',
      render: (text: string) => <Text code style={{ fontSize: 12 }}>{text}</Text>,
    },
    {
      title: '发布人',
      dataIndex: 'publishedBy',
      key: 'publishedBy',
      render: (text: string) => text || '-',
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      render: (t: string) => t ? dayjs(t).format('MM-DD HH:mm:ss') : '-',
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: any) => (
        <Space size="small">
          {record.status === 'draft' && (
            <Popconfirm title="验证此草稿？" onConfirm={() => validateMutation.mutate(record.versionId)}>
              <Button type="link" size="small" icon={<CheckCircleOutlined />}>验证</Button>
            </Popconfirm>
          )}
          {record.status === 'validated' && (
            <Popconfirm title="发布此配置？" onConfirm={() => publishMutation.mutate(record.versionId)}>
              <Button type="link" size="small" icon={<SendOutlined />} danger>发布</Button>
            </Popconfirm>
          )}
          {record.status === 'active' && (
            <Button
              type="link" size="small" icon={<RollbackOutlined />}
              onClick={() => viewHistory(selectedType, record.configKey)}
            >
              回滚
            </Button>
          )}
          <Button
            type="link" size="small" icon={<HistoryOutlined />}
            onClick={() => viewHistory(selectedType, record.configKey)}
          >
            历史
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="配置管理"
      extra={
        <Space>
          <Select
            value={selectedType}
            onChange={setSelectedType}
            options={CONFIG_TYPES}
            style={{ width: 140 }}
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            创建草稿
          </Button>
        </Space>
      }
    >
      <Table
        dataSource={configs || []}
        columns={columns}
        rowKey="versionId"
        loading={isLoading}
        size="middle"
        pagination={false}
      />

      {/* 创建草稿弹窗 */}
      <Modal
        title="创建配置草稿"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()}
        confirmLoading={createMutation.isPending}
        width={600}
      >
        <Form form={createForm} layout="vertical" onFinish={createMutation.mutate}>
          <Form.Item name="type" label="配置类型" initialValue="STRATEGY">
            <Select options={CONFIG_TYPES} />
          </Form.Item>
          <Form.Item name="configKey" label="配置键" rules={[{ required: true, message: '请输入配置键' }]}>
            <Input placeholder="如: MacdCross, maxLoss, SMA" />
          </Form.Item>
          <Form.Item name="contentJson" label="配置内容 (JSON)" rules={[{ required: true, message: '请输入 JSON 内容' }]}>
            <TextArea rows={8} placeholder='{"enabled": true, "smaPeriod": 20}' />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input placeholder="变更说明" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 历史/回滚抽屉 */}
      <Drawer
        title={`配置历史: ${selectedKey}`}
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        width={500}
      >
        <Table
          dataSource={history}
          rowKey="versionId"
          size="small"
          pagination={false}
          columns={[
            {
              title: '版本',
              dataIndex: 'versionId',
              render: (text: string) => <Text code style={{ fontSize: 11 }}>{text}</Text>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              render: (status: string) => {
                const s = STATUS_MAP[status] || { color: 'default', text: status };
                return <Tag color={s.color}>{s.text}</Tag>;
              },
            },
            {
              title: '时间',
              dataIndex: 'updatedAt',
              render: (t: string) => t ? dayjs(t).format('MM-DD HH:mm') : '-',
            },
            {
              title: '操作',
              render: (_: any, record: any) => {
                if (record.status === 'rolled_back' || record.status === 'archived') {
                  const activeVersion = history.find((h: any) => h.status === 'active');
                  if (activeVersion) {
                    return (
                      <Popconfirm
                        title={`回滚到此版本？`}
                        onConfirm={() => {
                          rollbackMutation.mutate({
                            versionId: activeVersion.versionId,
                            targetVersionId: record.versionId,
                          });
                          setHistoryOpen(false);
                        }}
                      >
                        <Button type="link" size="small" icon={<RollbackOutlined />}>
                          回滚到此版本
                        </Button>
                      </Popconfirm>
                    );
                  }
                }
                return null;
              },
            },
          ]}
        />
      </Drawer>
    </Card>
  );
}
