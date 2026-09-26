---
license: MIT
metadata:
  version: "1.0.0"
  category: mobile
  sources:
    - Flutter Documentation
    - Riverpod Documentation
    - Bloc Library Documentation
translations:
  zh:
    name: "Flutter 开发"
    description: >-
      Flutter 跨平台应用开发指南。用于 Flutter 项目架构、状态管理、UI、路由、本地化、构建发布、测试和常见问题排查。
  en:
    name: "Flutter Development"
    description: >-
      Flutter cross-platform application development guide. Use for Flutter project architecture, state management, UI, routing, localization, build and release, testing, and common troubleshooting.
  zhHant:
    name: "Flutter 開發"
    description: >-
      Flutter 跨平台應用程式開發指南。用於 Flutter 專案架構、狀態管理、UI、路由、本地化、建置與發布、測試，以及常見問題排解。
  ja:
    name: "Flutter 開発"
    description: >-
      Flutter クロスプラットフォームアプリ開発ガイドです。Flutter のプロジェクトアーキテクチャ、状態管理、UI、ルーティング、ローカライゼーション、ビルドとリリース、テスト、一般的なトラブルシューティングに使用します。
  ko:
    name: "Flutter 개발"
    description: >-
      Flutter 크로스 플랫폼 애플리케이션 개발 가이드입니다. Flutter 프로젝트 아키텍처, 상태 관리, UI, 라우팅, 현지화, 빌드 및 출시, 테스트, 일반적인 문제 해결에 사용합니다.
  es:
    name: "Desarrollo con Flutter"
    description: >-
      Guía de desarrollo de aplicaciones multiplataforma con Flutter. Úsala para arquitectura de proyectos Flutter, gestión del estado, interfaces, enrutamiento, localización, compilación y publicación, pruebas y resolución de problemas habituales.
  fr:
    name: "Développement Flutter"
    description: >-
      Guide de développement d'applications multiplateformes avec Flutter. À utiliser pour l'architecture des projets Flutter, la gestion d'état, les interfaces, le routage, la localisation, la compilation et la publication, les tests et la résolution des problèmes courants.
  de:
    name: "Flutter-Entwicklung"
    description: >-
      Leitfaden für die plattformübergreifende App-Entwicklung mit Flutter. Verwenden für Flutter-Projektarchitektur, Zustandsverwaltung, UI, Routing, Lokalisierung, Build und Veröffentlichung, Tests sowie die Behebung gängiger Probleme.
  vi:
    name: "Phát triển Flutter"
    description: >-
      Hướng dẫn phát triển ứng dụng đa nền tảng bằng Flutter. Sử dụng cho kiến trúc dự án Flutter, quản lý trạng thái, UI, định tuyến, bản địa hóa, xây dựng và phát hành, kiểm thử cũng như khắc phục sự cố thường gặp.
  ar:
    name: "تطوير Flutter"
    description: >-
      دليل تطوير التطبيقات متعددة المنصات باستخدام Flutter. يُستخدم لهندسة مشروعات Flutter وإدارة الحالة وواجهة المستخدم والتوجيه والتوطين والبناء والإصدار والاختبار واستكشاف المشكلات الشائعة وإصلاحها.
  pt:
    name: "Desenvolvimento Flutter"
    description: >-
      Guia de desenvolvimento de aplicações multiplataforma com Flutter. Utilize para arquitetura de projetos Flutter, gestão de estado, interfaces, encaminhamento, localização, compilação e lançamento, testes e resolução de problemas comuns.
name: flutter-dev
description: |
  Flutter cross-platform development guide covering widget patterns, Riverpod/Bloc state management, GoRouter navigation, performance optimization, and platform-specific implementations. Includes const optimization, responsive layouts, testing strategies, and DevTools profiling.
  Use when: building Flutter apps, implementing state management (Riverpod/Bloc), setting up GoRouter navigation, creating custom widgets, optimizing performance, writing widget tests, cross-platform development.
---

# Flutter Development Guide

A practical guide for building cross-platform applications with Flutter 3 and Dart. Focuses on proven patterns, state management, and performance optimization.

## Quick Reference

### Widget Patterns

| Purpose | Component |
|---------|-----------|
| State management (simple) | `StateProvider` + `ConsumerWidget` |
| State management (complex) | `NotifierProvider` / `Bloc` |
| Async data | `FutureProvider` / `AsyncNotifierProvider` |
| Real-time streams | `StreamProvider` |
| Navigation | `GoRouter` + `context.go/push` |
| Responsive layout | `LayoutBuilder` + breakpoints |
| List display | `ListView.builder` |
| Complex scrolling | `CustomScrollView` + Slivers |
| Hooks | `HookWidget` + `useState/useEffect` |
| Forms | `Form` + `TextFormField` + validation |

### Performance Patterns

| Purpose | Solution |
|---------|----------|
| Prevent rebuilds | `const` constructors |
| Selective updates | `ref.watch(provider.select(...))` |
| Isolate repaints | `RepaintBoundary` |
| Lazy lists | `ListView.builder` |
| Heavy computation | `compute()` isolate |
| Image caching | `cached_network_image` |

## Core Principles

### Widget Optimization
- Use `const` constructors wherever possible
- Extract static widgets to separate const classes
- Use `Key` for list items (ValueKey, ObjectKey)
- Prefer `ConsumerWidget` over `StatefulWidget` for state

### State Management
- Riverpod for dependency injection and simple state
- Bloc/Cubit for event-driven workflows and complex logic
- Never mutate state directly (create new instances)
- Use `select()` to minimize rebuilds

### Layout
- 8pt spacing increments (8, 16, 24, 32, 48)
- Responsive breakpoints: mobile (<650), tablet (650-1100), desktop (>1100)
- Support all screen sizes with flexible layouts
- Follow Material 3 / Cupertino design guidelines

### Performance
- Profile with DevTools before optimizing
- Target <16ms frame time for 60fps
- Use `RepaintBoundary` for complex animations
- Offload heavy work with `compute()`

## Checklist

### Widget Best Practices
- [ ] `const` constructors on all static widgets
- [ ] Proper `Key` on list items
- [ ] `ConsumerWidget` for state-dependent widgets
- [ ] No widget building inside `build()` method
- [ ] Extract reusable widgets to separate files

### State Management
- [ ] Immutable state objects
- [ ] `select()` for granular rebuilds
- [ ] Proper provider scoping
- [ ] Dispose controllers and subscriptions
- [ ] Handle loading/error states

### Navigation
- [ ] GoRouter with typed routes
- [ ] Auth guards via redirect
- [ ] Deep linking support
- [ ] State preservation across routes

### Performance
- [ ] Profile mode testing (`flutter run --profile`)
- [ ] <16ms frame rendering time
- [ ] No unnecessary rebuilds (DevTools check)
- [ ] Images cached and resized
- [ ] Heavy computation in isolates

### Testing
- [ ] Widget tests for UI components
- [ ] Unit tests for business logic
- [ ] Integration tests for user flows
- [ ] Bloc tests with `blocTest()`

## References

| Topic | Reference |
|-------|-----------|
| Widget patterns, const optimization, responsive layout | [Widget Patterns](references/widget-patterns.md) |
| Riverpod providers, notifiers, async state | [Riverpod State Management](references/riverpod-state.md) |
| Bloc, Cubit, event-driven state | [Bloc State Management](references/bloc-state.md) |
| GoRouter setup, routes, deep linking | [GoRouter Navigation](references/gorouter-navigation.md) |
| Feature-based structure, dependencies | [Project Structure](references/project-structure.md) |
| Profiling, const optimization, DevTools | [Performance Optimization](references/performance.md) |
| Widget tests, integration tests, mocking | [Testing Strategies](references/testing.md) |
| iOS/Android/Web specific implementations | [Platform Integration](references/platform-specific.md) |
| Implicit/explicit animations, Hero, transitions | [Animations](references/animations.md) |
| Dio, interceptors, error handling, caching | [Networking](references/networking.md) |
| Form validation, FormField, input formatters | [Forms](references/forms.md) |
| i18n, flutter_localizations, intl | [Localization](references/localization.md) |

---

Flutter, Dart, Material Design, and Cupertino are trademarks of Google LLC and Apple Inc. respectively. Riverpod, Bloc, and GoRouter are open-source packages by their respective maintainers.
