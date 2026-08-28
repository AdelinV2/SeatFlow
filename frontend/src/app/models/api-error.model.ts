export interface ValidationErrorDetail {
  field: string;
  message: string;
  rejectedValue?: unknown;
}

export interface ApiErrorResponse {
  status: number;
  error: string;
  errorCode: string;
  message: string;
  path: string;
  timestamp: string;
  correlationId?: string;
  validationErrors?: ValidationErrorDetail[];
}
