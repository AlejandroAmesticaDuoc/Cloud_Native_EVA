import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { ReportsApiService } from '../../core/services/reports-api.service';
import { LeadTimeResponse, SummaryResponse } from '../../core/models/report.model';

@Component({
  selector: 'app-reports',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './reports.html',
  styleUrl: './reports.css'
})
export class Reports implements OnInit {
  private readonly reportsApi = inject(ReportsApiService);

  hours = signal(24);
  summary = signal<SummaryResponse | null>(null);
  leadTime = signal<LeadTimeResponse | null>(null);
  loading = signal(true);
  error = signal('');

  readonly statusKeys = [
    'CREADO',
    'ACEPTADO',
    'EN_PREPARACION',
    'DESPACHADO',
    'ENTREGADO',
    'CANCELADO'
  ];

  ngOnInit(): void {
    this.loadReports();
  }

  loadReports(): void {
    this.loading.set(true);
    this.error.set('');

    forkJoin({
      summary: this.reportsApi.getSummary(this.hours()),
      leadTime: this.reportsApi.getLeadTime()
    }).subscribe({
      next: ({ summary, leadTime }) => {
        this.summary.set(summary);
        this.leadTime.set(leadTime);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error cargando reportes:', err);
        this.error.set(err?.error?.message || 'No fue posible cargar los reportes desde el BFF.');
        this.loading.set(false);
      }
    });
  }

  onHoursChange(value: number): void {
    this.hours.set(Number(value));
    this.loadReports();
  }

  formatSeconds(seconds: number | null | undefined): string {
    if (seconds === null || seconds === undefined) {
      return 'Sin datos';
    }
    if (seconds < 60) {
      return `${seconds.toFixed(1)} s`;
    }
    const mins = Math.floor(seconds / 60);
    const rem = Math.round(seconds % 60);
    return `${mins}m ${rem}s`;
  }
}
