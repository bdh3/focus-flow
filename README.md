# 포커스 플로우 (Focus Flow)

집중의 흐름을 유지하고 시간을 시각적으로 관리하는 안드로이드 앱입니다. 뽀모도로 기법과 유사하지만, 사용자가 자신의 리듬에 맞게 시간을 설정하고 끊김 없는 흐름(Flow)을 유지하는 데 중점을 둡니다.

## 🎯 주요 목표

ADHD 성향을 가진 사용자들이 시간의 흐름을 직관적으로 인지하고, '집중'과 '휴식'의 경계를 명확히 하여 번아웃 없이 꾸준히 작업을 이어갈 수 있도록 돕습니다.

## ✨ 주요 기능

- **유연한 블록 기반 시간 관리**: 1시간을 기본 단위로 하여 최소 1개에서 최대 60개까지 자유롭게 블록을 쪼갤 수 있습니다. (1분 단위 테스트 모드 지원)
- **사용자 정의 집중/휴식 비율**: 쪼개진 블록 중 집중 시간과 휴식 시간의 개수를 직접 설정하여 자신만의 사이클을 만듭니다. (예: 50분 집중/10분 휴식, 25분 집중/5분 휴식 등)
- **포커스 플로우 특화 중간 알림(Interval Notification)**: 집중 시간 동안 설정한 간격(1~15분)마다 짧은 진동을 주어 잡생각에 빠지지 않도록 주의를 환기합니다.
- **백그라운드 안정성**: 배터리 최적화 제외 권한 및 `System.currentTimeMillis()` 기반 로직으로 화면이 꺼져도 타이머가 정확하게 유지됩니다.
- **알림 및 바로가기**: 블록 종료 시 푸시 알림을 제공하며, 알림 클릭 시 즉시 앱으로 복귀하여 다음 블록을 시작할 수 있습니다.
- **캘린더 자동 기록**: 완료된 집중 세션을 기기 캘린더(삼성, 구글 등)에 자동으로 기록하여 시간 사용 내역을 시각화합니다.
- **작업(Task) 관리**: 할 일 목록을 작성하고 집중 시간 동안 완료 여부를 체크할 수 있습니다.
- **통계 리포트**: 일별 집중 시간과 작업 완료 통계를 통해 성취감을 제공합니다.

## 🛠️ 기술 스택 및 개발 환경

- **Platform**: Android
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM (Model-View-ViewModel)
- **Asynchronous**: Coroutines
- **Local DB**: Room (작업, 설정 등 저장)

## 📁 프로젝트 구조 (예상)

```
.
├── app
│   ├── build.gradle
│   └── src
│       └── main
│           ├── java/com/example/adhdblockscheduler
│           │   ├── data         // 데이터 소스 (Room, Repository)
│           │   ├── di           // 의존성 주입
│           │   ├── model        // 데이터 모델 (Task, Block, etc.)
│           │   ├── ui           // UI (Composable Screens, ViewModel, Theme)
│           │   │   ├── screen   // 각 화면 Composable
│           │   │   ├── viewmodel// ViewModel
│           │   │   └── theme    // 앱 테마
│           │   └── util         // 유틸리티 클래스 (알림, 시간 계산 등)
│           ├── res
│           └── AndroidManifest.xml
└── ...
```

## 🚀 빌드 및 실행 방법

1.  **Prerequisites**: Android Studio (최신 버전 권장), JDK 11 이상
2.  **Download APK**: [GitHub Releases](https://github.com/bdh3/adhd-block-scheduler/releases)에서 최신 버전(v1.0.2 이상)의 APK를 직접 다운로드할 수 있습니다.
3.  **SSH Clone**:
    ```bash
    git clone git@github.com-adhd-block-scheduler:bdh3/adhd-block-scheduler.git
    ```
4.  Android Studio에서 프로젝트를 엽니다.
4.  필요한 Gradle 종속성이 동기화될 때까지 기다립니다.
5.  에뮬레이터 또는 실제 기기에서 앱을 실행합니다.

## 🗺️ 향후 계획 (Roadmap)

- [x] 다양한 테마 (다크 모드 포함)
- [x] 캘린더 연동 (앱 -> 기기 캘린더 기록)
- [x] 실시간 통계 (최근 7일 집중 시간 및 작업 완료율)
- [ ] 캘린더 양방향 동기화 (기기 일정 불러오기)
- [ ] 위젯 지원
- [ ] 소리/진동 커스터마이징
- [ ] 클라우드 동기화
