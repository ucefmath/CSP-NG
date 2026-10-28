import { Link } from "react-router";
import { useT } from "../i18n";

export function NotFoundPage() {
  const t = useT();

  return (
    <div className="card">
      <h1>{t("login.notFoundTitle")}</h1>
      <p className="muted">{t("login.notFoundDesc")}</p>
      <p>
        <Link to="../overview" className="btn btn-primary btn-sm">
          {t("login.notFoundBack")}
        </Link>
      </p>
    </div>
  );
}
