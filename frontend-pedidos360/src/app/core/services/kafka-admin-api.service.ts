import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  ConsumerGroupSummary,
  CreateTopicRequest,
  OperationResponse,
  PublishMessageRequest,
  TopicSummary,
  UpdateTopicConfigRequest
} from '../models/kafka-admin.model';

@Injectable({
  providedIn: 'root'
})
export class KafkaAdminApiService {

  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.api.baseUrl}/kafka-admin`;

  listTopics(): Observable<TopicSummary[]> {
    return this.http.get<TopicSummary[]>(`${this.baseUrl}/topics`);
  }

  createTopic(request: CreateTopicRequest): Observable<OperationResponse> {
    return this.http.post<OperationResponse>(`${this.baseUrl}/topics`, request);
  }

  updateTopic(name: string, request: UpdateTopicConfigRequest): Observable<OperationResponse> {
    return this.http.patch<OperationResponse>(`${this.baseUrl}/topics/${encodeURIComponent(name)}`, request);
  }

  deleteTopic(name: string): Observable<OperationResponse> {
    return this.http.delete<OperationResponse>(`${this.baseUrl}/topics/${encodeURIComponent(name)}`);
  }

  listConsumerGroups(): Observable<ConsumerGroupSummary[]> {
    return this.http.get<ConsumerGroupSummary[]>(`${this.baseUrl}/consumer-groups`);
  }

  publishMessage(request: PublishMessageRequest): Observable<OperationResponse> {
    return this.http.post<OperationResponse>(`${this.baseUrl}/messages`, request);
  }
}
