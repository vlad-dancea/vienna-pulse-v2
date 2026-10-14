# Vienna Pulse

A live map of Vienna public transport. The repository contains a Spring Boot backend
in `vienna-pulse-backend/` and an Angular frontend in `vienna-pulse-frontend/`.

## Tools and checks

- mise owns tool versions and tasks. Use `mise run dev`, `mise run test`, `mise run format`
  and `mise run check` at the repository root, or `mise run //project-name:task` for one project.
- The root `mise.toml` pins the tools; each project's `mise.toml` defines its tasks.
- Use pnpm for frontend dependencies and commit its lockfile. Keep direct dependencies pinned.
- Keep CI checks identical to local checks, with one path-filtered workflow per project,
  read-only permissions and actions pinned to full commit SHAs.
- Run `mise run check` after changes and report exactly what ran and any limitations.

## Documentation and tests

- Document public types in one concise sentence. Use Java `///` Markdown comments and
  TypeScript `/** ... */` comments. Document methods only when the signature hides something.
- Comments explain why a choice is needed, not what the next line does. Avoid parameter
  lists, tutorial boilerplate and speculative documentation.
- Write few, meaningful tests. Avoid trivial construction, accessor and enum tests.
  Assert whole records or messages where practical and verify tests fail when behavior breaks.
- Verify importer behavior against the real Wiener Linien feed when changing ingestion,
  use temporary files and clean them up afterwards.

## Backend

- Follow the existing package structure and Java records. Mark every new package with
  `@NullMarked` in `package-info.java` so NullAway checks it.
- Use the formatter enforced by Spotless. The build's compiler, Error Prone, NullAway and
  doclint checks are the source of truth when editor diagnostics differ.

## Frontend

- For Angular implementation, read `.agents/skills/angular-developer/SKILL.md` and the
  references relevant to the task.
- Use Angular 22 standalone components, zoneless change detection, the router, Tailwind CSS
  and Vitest. Rendering is client-side. Follow current Angular documentation and installed types.
- Keep application code separate from backend ingestion and storage concerns.
- TypeScript strict checking, Angular strict templates and OnPush are defaults in this toolchain;
  do not disable them or add redundant configuration.
- Use the local Angular MCP server through `mise run //vienna-pulse-frontend:mcp`.
  Install dependencies first with `mise run //vienna-pulse-frontend:install`.
- Prettier owns formatting and Tailwind class order. Typed ESLint and Angular template
  accessibility rules run in `check`; warnings fail the check.

## TypeScript Best Practices

- Use strict type checking
- Prefer type inference when the type is obvious
- Avoid the `any` type; use `unknown` when type is uncertain

## Angular Best Practices

- Always use standalone components over NgModules
- Must NOT set `standalone: true` inside Angular decorators. It's the default in Angular v20+.
- Do NOT set `changeDetection: ChangeDetectionStrategy.OnPush` explicitly. `OnPush` is the default in Angular v22+.
- Use signals for state management
- Implement lazy loading for feature routes
- Do NOT use the `@HostBinding` and `@HostListener` decorators. Put host bindings inside the `host` object of the `@Component` or `@Directive` decorator instead
- Use `NgOptimizedImage` for all static images.
  - `NgOptimizedImage` does not work for inline base64 images.

## Accessibility Requirements

- It MUST pass all AXE checks.
- It MUST follow all WCAG AA minimums, including focus management, color contrast, and ARIA attributes.

### Components

- Keep components small and focused on a single responsibility
- Use `input()` and `output()` functions instead of decorators
- Use `model()` for two-way bound properties with `[(prop)]` syntax instead of pairing `input()` with `output()`
- Use `computed()` for derived state
- Use `linkedSignal()` for state derived from multiple reactive sources that must stay synchronized
- Prefer inline templates for small components
- Prefer Signal Forms (`@angular/forms/signals`) for new forms. They are stable in Angular v22+ and provide signal-based state, type-safe field access, and schema-based validation
- When not using Signal Forms, prefer Reactive forms instead of Template-driven ones
- Do NOT use `ngClass`, use `class` bindings instead
- Do NOT use `ngStyle`, use `style` bindings instead
- Do NOT import `CommonModule`, import only the directives and pipes the template uses, such as `AsyncPipe` or `DatePipe`
- When using external templates/styles, use paths relative to the component TS file.

## State Management

- Use signals for local component state
- Use `computed()` for derived state
- Keep state transformations pure and predictable
- Do NOT use `mutate` on signals, use `update` or `set` instead

## Templates

- Keep templates simple and avoid complex logic
- Use native control flow (`@if`, `@for`, `@switch`) instead of `*ngIf`, `*ngFor`, `*ngSwitch`
- Use the async pipe to handle observables
- Do not assume globals like (`new Date()`) are available.

## Services

- Design services around a single responsibility
- Use the `providedIn: 'root'` option for singleton services
- Prefer the `@Service` decorator over `@Injectable({providedIn: 'root'})` for new singleton services (Angular v22+)
- Use the `inject()` function instead of constructor injection
