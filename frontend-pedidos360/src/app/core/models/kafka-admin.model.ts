export interface TopicSummary {
  name: string;
  partitions: number;
  replicationFactor: number;
  retentionMs: string;
  cleanupPolicy: string;
  protectedTopic: boolean;
}

export interface CreateTopicRequest {
  name: string;
  partitions: number;
  replicationFactor: number;
  retentionMs?: string;
  cleanupPolicy?: string;
}

export interface UpdateTopicConfigRequest {
  retentionMs?: string;
  cleanupPolicy?: string;
  newPartitionCount?: number | null;
}

export interface PublishMessageRequest {
  topic: string;
  key: string;
  eventType: string;
  payload: string;
}

export interface ConsumerGroupSummary {
  groupId: string;
  state: string;
  memberCount: number;
  assignedTopics: string[];
}

export interface OperationResponse {
  success: boolean;
  message: string;
  details: Record<string, unknown>;
}
