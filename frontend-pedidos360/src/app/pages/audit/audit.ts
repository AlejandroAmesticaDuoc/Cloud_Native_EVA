import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuditApiService } from '../../core/services/audit-api.service';
import { AuditRecord } from '../../core/models/audit.model';

@Component({
  selector: 'app-audit',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './audit.html',
  styleUrl: './audit.css'
})
export class Audit implements OnInit {
  private readonly auditApi = inject(AuditApiService);

  records = signal<AuditRecord[]>([]);
  nextAfterId = signal<number | null>(null);
  orderIdFilter = signal<string>('');
  loading = signal(true);
  loadingMore = signal(false);
  error = signal('');

  ngOnInit(): void {
    this.loadEvents(true);
  }

  loadEvents(reset = true): void {
    if (reset) {
      this.loading.set(true);
      this.error.set('');
      this.records.set([]);
      this.nextAfterId.set(null);
    } else {
      this.loadingMore.set(true);
    }

    const afterId = reset ? 0 : (this.nextAfterId() ?? 0);
    const rawOrderId = this.orderIdFilter().trim();
    const parsedOrderId = rawOrderId ? Number(rawOrderId) : null;

    const request$ = parsedOrderId && parsedOrderId > 0
      ? this.auditApi.getOrderEvents(parsedOrderId, afterId, 50)
      : this.auditApi.getEvents(afterId, 50);

    request$.subscribe({
      next: (page) => {
        this.records.update((current) => reset ? page.items : [...current, ...page.items]);
        this.nextAfterId.set(page.nextAfterId);
        this.loading.set(false);
        this.loadingMore.set(false);
      },
      error: (err) => {
        console.error('Error cargando auditoría:', err);
        this.error.set(err?.error?.message || 'No fue posible consultar los eventos de auditoría.');
        this.loading.set(false);
        this.loadingMore.set(false);
      }
    });
  }

  clearFilter(): void {
    this.orderIdFilter.set('');
    this.loadEvents(true);
  }
}
