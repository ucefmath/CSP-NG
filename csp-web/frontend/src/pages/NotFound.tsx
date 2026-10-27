import { Link } from "react-router";

export function NotFoundPage() {
  return (
    <div className="card">
      <h1>Page introuvable</h1>
      <p className="muted">Cette route d'administration n'existe pas.</p>
      <p>
        <Link to="../overview" className="btn btn-primary btn-sm">
          Retour à la vue d'ensemble
        </Link>
      </p>
    </div>
  );
}
