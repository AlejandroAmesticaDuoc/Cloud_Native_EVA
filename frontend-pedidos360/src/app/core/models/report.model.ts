export interface HourlySales {
  hour: string;
  deliveredOrders: number;
  deliveredAmount: number;
}

export interface SummaryResponse {
  totalOrders: number;
  activeOrders: number;
  deliveredOrders: number;
  cancelledOrders: number;
  ordersByStatus: Record<string, number>;
  deliveredAmount: number;
  hourlyFrom: string;
  hourlyTo: string;
  salesByHour: HourlySales[];
}

export interface LeadTimeResponse {
  deliveredOrders: number;
  averageSeconds: number | null;
  minimumSeconds: number | null;
  maximumSeconds: number | null;
}
