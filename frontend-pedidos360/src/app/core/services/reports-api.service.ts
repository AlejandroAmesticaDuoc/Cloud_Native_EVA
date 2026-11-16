import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { LeadTimeResponse, SummaryResponse } from '../models/report.model';

@Injectable({
  providedIn: 'root'
})
export class ReportsApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.api.baseUrl}/reports`;

  getSummary(hours = 24): Observable<SummaryResponse> {
    const params = new HttpParams().set('hours', hours.toString());
    return this.http.get<SummaryResponse>(`${this.baseUrl}/summary`, { params });
  }

  getLeadTime(): Observable<LeadTimeResponse> {
    return this.http.get<LeadTimeResponse>(`${this.baseUrl}/lead-time`);
  }
}
