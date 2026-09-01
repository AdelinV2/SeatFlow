import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { PagedResult } from '../models/event.model';
import { UserProfile } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class AdminUserApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/users';

  getUsers(options?: {
    page?: number;
    size?: number;
    sort?: string;
    direction?: 'asc' | 'desc';
  }): Observable<PagedResult<UserProfile>> {
    let params = new HttpParams();
    if (options?.page !== undefined) {
      params = params.set('page', options.page.toString());
    }
    if (options?.size !== undefined) {
      params = params.set('size', options.size.toString());
    }
    if (options?.sort) {
      params = params.set('sort', options.sort);
    }
    if (options?.direction) {
      params = params.set('direction', options.direction);
    }

    return this.http.get<PagedResult<UserProfile>>(this.baseUrl, { params });
  }
}
