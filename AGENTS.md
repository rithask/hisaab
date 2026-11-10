# Notes for coding agents

Read **[docs/principles.md](docs/principles.md)** before changing anything. It covers the product, UI design and
architecture rules this app follows, and the checklist for adding an optional feature.

Quick reference:
- Backend: `backend/` (Java 25, Spring Boot 4, PostgreSQL, Flyway). Test: `cd backend && ./mvnw test`.
- Frontend: `frontend/` (React 19, TypeScript, Vite, Tailwind 4, TanStack Query). Check: `cd frontend && npm run build`.
- Run locally: `README.md` → *Run locally*. Mock data: `backend/seed/seed_mock_data.py`.
- Versions and releases: `docs/versioning.md`. Record user-visible changes in `CHANGELOG.md` under *Unreleased*.
- Never commit real personal data (names, account numbers, statements, SMS, emails, passwords). Test data is made up.
- Don't commit or push unless asked.
