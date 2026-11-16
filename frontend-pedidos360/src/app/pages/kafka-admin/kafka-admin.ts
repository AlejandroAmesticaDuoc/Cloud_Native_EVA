import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { KafkaAdminApiService } from '../../core/services/kafka-admin-api.service';
import { AuthApiService } from '../../core/services/auth-api.service';
import {
  ConsumerGroupSummary,
  CreateTopicRequest,
  PublishMessageRequest,
  TopicSummary,
  UpdateTopicConfigRequest
} from '../../core/models/kafka-admin.model';

@Component({
  selector: 'app-kafka-admin',
  imports: [FormsModule, RouterLink],
  templateUrl: './kafka-admin.html',
  styleUrl: './kafka-admin.css'
})
export class KafkaAdmin implements OnInit {

  private readonly kafkaApi = inject(KafkaAdminApiService);
  private readonly authApi = inject(AuthApiService);

  topics = signal<TopicSummary[]>([]);
  consumerGroups = signal<ConsumerGroupSummary[]>([]);

  loading = signal(true);
  error = signal('');
  feedback = signal('');
  isAdmin = signal(false);

  showCreateForm = signal(false);
  editingTopicName = signal<string | null>(null);

  newTopic: CreateTopicRequest = {
    name: '',
    partitions: 1,
    replicationFactor: 1,
    retentionMs: '604800000',
    cleanupPolicy: 'delete'
  };

  editConfig: UpdateTopicConfigRequest = {
    retentionMs: '',
    cleanupPolicy: 'delete',
    newPartitionCount: null
  };

  messageForm: PublishMessageRequest = {
    topic: '',
    key: 'test-key-1',
    eventType: 'TEST_EVENT',
    payload: '{"mensaje":"Prueba desde Pedidos360"}'
  };

  ngOnInit(): void {
    this.loadUser();
    this.refreshAll();
  }

  loadUser(): void {
    this.authApi.getCurrentUser().subscribe({
      next: (user) => this.isAdmin.set(user.roles.includes('ADMIN')),
      error: (err) => console.error('Error obteniendo usuario:', err)
    });
  }

  refreshAll(): void {
    this.loading.set(true);
    this.error.set('');

    this.kafkaApi.listTopics().subscribe({
      next: (topics) => {
        this.topics.set(topics);
        if (!this.messageForm.topic && topics.length > 0) {
          this.messageForm.topic = topics[0].name;
        }
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error listando tópicos Kafka:', err);
        this.error.set('No fue posible obtener los tópicos de Kafka.');
        this.loading.set(false);
      }
    });

    this.kafkaApi.listConsumerGroups().subscribe({
      next: (groups) => this.consumerGroups.set(groups),
      error: (err) => console.error('Error listando consumer groups:', err)
    });
  }

  createTopic(): void {
    if (!this.newTopic.name.trim() || this.newTopic.partitions < 1) {
      return;
    }

    this.kafkaApi.createTopic(this.newTopic).subscribe({
      next: (res) => {
        this.notify(res.message || `Tópico "${this.newTopic.name}" creado.`);
        this.newTopic = {
          name: '',
          partitions: 1,
          replicationFactor: 1,
          retentionMs: '604800000',
          cleanupPolicy: 'delete'
        };
        this.showCreateForm.set(false);
        this.refreshAll();
      },
      error: (err) => {
        console.error('Error creando tópico:', err);
        this.error.set(err?.error?.message || 'No fue posible crear el tópico.');
      }
    });
  }

  startEdit(topic: TopicSummary): void {
    this.editingTopicName.set(topic.name);
    this.editConfig = {
      retentionMs: topic.retentionMs || '604800000',
      cleanupPolicy: topic.cleanupPolicy || 'delete',
      newPartitionCount: topic.partitions
    };
  }

  cancelEdit(): void {
    this.editingTopicName.set(null);
  }

  saveTopicConfig(topic: TopicSummary): void {
    const payload: UpdateTopicConfigRequest = {
      retentionMs: this.editConfig.retentionMs,
      cleanupPolicy: this.editConfig.cleanupPolicy
    };

    if (
      this.editConfig.newPartitionCount &&
      this.editConfig.newPartitionCount > topic.partitions
    ) {
      payload.newPartitionCount = this.editConfig.newPartitionCount;
    }

    this.kafkaApi.updateTopic(topic.name, payload).subscribe({
      next: (res) => {
        this.cancelEdit();
        this.notify(res.message || `Configuración de "${topic.name}" actualizada.`);
        this.refreshAll();
      },
      error: (err) => {
        console.error('Error actualizando tópico:', err);
        this.error.set(err?.error?.message || 'No fue posible actualizar el tópico.');
      }
    });
  }

  deleteTopic(topic: TopicSummary): void {
    if (topic.protectedTopic) {
      return;
    }

    if (!confirm(`¿Deseas eliminar el tópico "${topic.name}"?`)) {
      return;
    }

    this.kafkaApi.deleteTopic(topic.name).subscribe({
      next: (res) => {
        this.notify(res.message || `Tópico "${topic.name}" eliminado.`);
        this.refreshAll();
      },
      error: (err) => {
        console.error('Error eliminando tópico:', err);
        this.error.set(err?.error?.message || 'No fue posible eliminar el tópico.');
      }
    });
  }

  publishMessage(): void {
    if (
      !this.messageForm.topic.trim() ||
      !this.messageForm.key.trim() ||
      !this.messageForm.eventType.trim() ||
      !this.messageForm.payload.trim()
    ) {
      return;
    }

    this.kafkaApi.publishMessage(this.messageForm).subscribe({
      next: (res) => {
        this.notify(res.message || `Mensaje publicado en "${this.messageForm.topic}".`);
      },
      error: (err) => {
        console.error('Error publicando mensaje:', err);
        this.error.set(err?.error?.message || 'No fue posible publicar el mensaje.');
      }
    });
  }

  private notify(msg: string): void {
    this.error.set('');
    this.feedback.set(msg);
    setTimeout(() => this.feedback.set(''), 4500);
  }
}
