import type { ChangeEvent } from "react";
import { useT } from "../i18n";

export interface PaginationProps {
  page: number; // 1-based
  totalPages: number;
  totalElements: number;
  pageSize: number;
  pageSizeOptions?: number[];
  onPageChange: (newPage: number) => void;
  onPageSizeChange?: (newPageSize: number) => void;
  label?: string;
}

export function Pagination({
  page,
  totalPages,
  totalElements,
  pageSize,
  pageSizeOptions = [10, 25, 50, 100],
  onPageChange,
  onPageSizeChange,
  label,
}: PaginationProps) {
  const t = useT();
  const safeTotalPages = Math.max(1, totalPages);
  const safePage = Math.min(Math.max(1, page), safeTotalPages);
  const canPrev = safePage > 1;
  const canNext = safePage < safeTotalPages;
  const itemLabel = label ?? t("components.items");

  return (
    <nav className="pagination" aria-label={t("components.pagination")}>
      <div className="pagination-info">
        <span>
          {t("components.page")} <strong>{totalElements === 0 ? 0 : safePage}</strong> / <strong>{totalPages}</strong> — {totalElements} {itemLabel}
        </span>
      </div>
      <div className="pagination-controls">
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={!canPrev}
          aria-label={t("components.firstPage")}
          onClick={() => onPageChange(1)}
        >
          «
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={!canPrev}
          aria-label={t("components.prevPage")}
          onClick={() => onPageChange(safePage - 1)}
        >
          ‹
        </button>
        <span className="pagination-current-page" aria-current="page">
          {totalElements === 0 ? 0 : safePage}
        </span>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={!canNext}
          aria-label={t("components.nextPage")}
          onClick={() => onPageChange(safePage + 1)}
        >
          ›
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={!canNext}
          aria-label={t("components.lastPage")}
          onClick={() => onPageChange(safeTotalPages)}
        >
          »
        </button>
      </div>
      {onPageSizeChange && (
        <div className="pagination-size">
          <label htmlFor="pagination-size-select" className="small muted">
            {t("components.rowsPerPage")}
          </label>
          <select
            id="pagination-size-select"
            className="pagination-select"
            value={pageSize}
            onChange={(e: ChangeEvent<HTMLSelectElement>) => onPageSizeChange(Number(e.target.value))}
            aria-label={t("components.itemsPerPage")}
          >
            {pageSizeOptions.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        </div>
      )}
    </nav>
  );
}
