export interface Product {
  id: number;
  name: string;
  price: number;
  stock: number;
  active: boolean;
}

export interface CreateProductRequest {
  name: string;
  price: number;
  stock: number;
}

export interface UpdateProductRequest {
  name: string;
  price: number;
}

export interface UpdateProductStockRequest {
  stock: number;
}