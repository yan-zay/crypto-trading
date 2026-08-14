type MessageApi = {
  success: (content: string) => void;
  error: (content: string) => void;
  warning: (content: string) => void;
};

let messageApi: MessageApi | null = null;

export function setMessageApi(api: MessageApi | null) {
  messageApi = api;
}

export const notify = {
  success(content: string) {
    messageApi?.success(content);
  },
  error(content: string) {
    messageApi?.error(content);
  },
  warning(content: string) {
    messageApi?.warning(content);
  },
};
