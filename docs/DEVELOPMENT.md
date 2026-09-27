# 개발 안내

코드를 고치려는 사람을 위한 문서입니다. **처음 띄우는 방법은
[README](../README.md)** 를 먼저 보세요.

---

## 1. 개발 환경

### Docker 없이 (권장 — 고치면서 바로 확인)

```bash
make infra            # PostgreSQL만 컨테이너로 띄웁니다

make dev-collector    # 터미널 1 — 자동 리로드 (collector/.venv 필요)
make dev-backend      # 터미널 2
make dev-frontend     # 터미널 3 — 핫 리로드
```

수집기 가상환경이 없으면 만듭니다.

```bash
cd collector
python3.11 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt   # 운영 의존성 + 테스트·린트 도구
```

`make`가 `collector/.venv`를 먼저 찾으므로, conda base 같은 다른 파이썬이 PATH
앞에 있어도 올바른 인터프리터를 씁니다.

### 전부 Docker로

```bash
make up          # 코드를 고치면 다시 빌드해야 반영됩니다
make logs S=backend
```

---

## 2. 테스트

```bash
make test              # 세 가지 전부
make test-collector    # pytest
make test-backend      # JUnit (실제 PostgreSQL 사용)
make test-frontend     # 자가검증 + 린트 + 빌드(타입 검사)
```

### ⚠️ 테스트는 전용 DB에서 돕니다

백엔드 통합 테스트는 시작할 때마다 `snapshots` 테이블을 **비웁니다**. 그래서
`macrodash_test`를 씁니다. `make test-*`가 알아서 맞춰 주지만, 직접 실행할 때는
`TEST_DATABASE_URL`을 확인하세요.

```bash
# 직접 실행할 때
cd backend  && TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/macrodash_test mvn test
cd collector && TEST_DATABASE_URL=postgresql://macro:macro@localhost:5432/macrodash_test pytest
```

백엔드에는 `guardAgainstRealDatabase()`가 있어 DB 이름이 `_test`로 끝나지 않으면
테스트가 스스로 멈춥니다.

### 테스트가 고정하는 것

단순 커버리지가 아니라 **한 번씩 틀렸던 규칙**을 고정합니다.

| 테스트 | 무엇을 막는가 |
|---|---|
| `DatasetsParityTest` | 데이터셋 이름이 Python과 Java에서 갈라지는 것 ("수집은 되는데 화면에 안 보임") |
| `AnalyticsMathTest` | 상관·국면·백테스트 계산이 표본 부족에도 숫자를 만들어내는 것 |
| `GuruAndRiskTest` | 위험 기여의 합이 포트폴리오 변동성과 어긋나는 것 |
| `SectorSeriesAlignmentTest` | 날짜와 종가 배열이 한 칸씩 밀리는 것 |
| `KstTest` | 서버 시간대(UTC) 때문에 날짜가 하루 어긋나는 것 |
| `StoreReaderNonBlockingTest` | 화면이 수집을 기다리게 되는 것 |
| `test_equities.py` | 모르는 종목 이름에 엉뚱한 티커가 붙는 것 |
| `marketCalendar.check.mts` | 공휴일과 거래소 휴장일을 혼동하는 것 |

---

## 3. 기능을 하나 붙이기

데이터가 필요한 기능은 **네 계층을 순서대로** 지납니다.

### ① 무엇을 받을지 정의 — `collector/app/indicators.py`

티커·FRED 시리즈·COT 자산·기관 목록이 전부 여기 있습니다. 화면에만 추가하면
"왜 항상 비어 있지?"가 됩니다.

### ② 받아서 저장 — `collector/app/tasks.py` + `catalog.py`

```python
# catalog.py — 저장본 이름 (백엔드와 같은 문자열이어야 합니다)
SNAP_MY_THING = "domain.my_thing"

# tasks.py
def task_my_thing() -> str:
    payload = my_service.collect(...)
    if not payload:
        raise EmptyResult("0건 — 기존 저장본 유지")   # 빈 결과로 덮지 않습니다
    store.put_snapshot(catalog.SNAP_MY_THING, payload)
    return "N건 수집"

ALL_TASKS = (..., Task("my_thing", "slow", task_my_thing, "설명"),)
```

`store.py`의 기대 목록(`expected`)에도 추가하면 `make status`에 나타납니다.

### ③ 계산 — `backend/.../analytics/`

**순수 함수로 작성하고 테스트를 여기에 붙입니다.** 저장소나 스프링에 의존하지
않게 두면 테스트가 빠르고 규칙이 분명해집니다.

```java
public final class MyMath {
    /** 설명. 표본이 부족하면 null (0으로 채우지 않습니다). */
    public static Double compute(List<Double> values) { ... }
}
```

### ④ 응답 조립 — `backend/.../service/` + `web/` 메뉴별 컨트롤러

`Datasets.java`에 ②에서 정한 이름을 **똑같이** 적습니다.

```java
// Datasets.java
public static final String SNAP_MY_THING = "domain.my_thing";
```

`DatasetsParityTest`가 실제로 `catalog.py`를 읽어 대조하므로, 한쪽만 고치면
빌드가 깨집니다.

응답에는 신선도를 반드시 넣습니다.

```java
snapshot.get().putFreshness(out);   // collectedAtKst · ageSeconds · stale
```

엔드포인트는 메뉴에 맞는 컨트롤러에 붙입니다(`MacroController`·`InstitutionController`·
`PositioningController`·`AnalyticsController`·`StatusController`, 출처가 따로인 것은
`FlowsController`·`PublicDataController`·`TossController`). 경로를 추가하면
`RouteInventoryTest`의 목록과 [API.md](API.md) 표에도 적어야 빌드가 통과합니다.
컨트롤러 하나가 서비스를 5개보다 많이 받으면 같은 테스트가 실패하니, 그때는 나눕니다.

### ⑤ 화면 — `frontend/src/app/(dashboard)/…`

`useApi` 훅으로 읽고, 값이 없으면 `EMPTY`(`—`)로 그립니다. 사이드바
(`components/Sidebar.tsx`)에 메뉴를 추가하세요.

---

## 4. 코드 규약

### 주석

```java
/**
 * 무엇을 하는가 (한 줄).
 *
 * <p>왜 이렇게 했는가 — 코드만 봐서는 알 수 없는 배경. 특히 "직관적인 방법을
 * 쓰지 않은 이유"를 적습니다.
 *
 * @param x 의미와 단위
 * @return  값의 의미. 언제 null인지
 */
```

- 코드를 그대로 옮긴 주석(`// i를 1 증가`)은 쓰지 않습니다.
- **값이 없을 때의 동작**은 반드시 적습니다 (이 프로젝트에서 가장 자주 틀리는 곳).
- 단위(%, %p, 조 달러, 억 원)를 적습니다.

### 숫자를 다룰 때

- 표본이 부족하면 `null`을 돌려주고 화면은 `—`로 그립니다. **0으로 채우지
  않습니다.**
- 추정치·대용값은 `isEstimated` / `isProxy` 플래그를 함께 내려보냅니다.
- 금액은 조·억 단위로 통일합니다 (`lib/format.ts`, `SnapshotTextService`).

자세한 규칙은 [PRINCIPLES.md](PRINCIPLES.md)에 있습니다.

---

## 5. Git

### 최신 코드 받기

```bash
make update && make up
```

`git pull`을 쓰지 마세요. 새 작업이 다른 브랜치에 있으면 아무것도 받지 않고
조용히 끝납니다. `make update`는 그 경우를 알려 줍니다.

### 브랜치 정리

```bash
git checkout main
git merge --ff-only origin/claude/<브랜치>
git push origin main
git push origin --delete claude/<브랜치>
git remote prune origin
```

### Git 문제 해결

| 증상 | 원인 / 해결 |
|---|---|
| `git push`가 `Permission to … denied` (403) | 저장소 권한이 아니라 **맥에 저장된 GitHub 토큰**이 만료됐거나 쓰기 권한이 없습니다. 공개 저장소는 pull에 로그인이 필요 없어 push할 때만 드러납니다. 아래 순서로 해결 |
| `refusing to allow a Personal Access Token to … workflow` | `.github/workflows/`를 고친 경우입니다. 토큰 설정에서 `workflow` 권한만 체크하면 됩니다 |
| `Permission denied (publickey)` | 원격이 SSH인데 키가 없습니다. `git remote -v` 확인 후 HTTPS로 변경 |
| `make update`가 `수정 중인 파일이 있어 당기지 않았습니다` | `git status` 확인 후 `git restore <파일>`(버리기) 또는 `git stash`(보관) |
| push는 안 되는데 코드는 최신 | 정상입니다. 받기와 올리기는 별개입니다. 화면 왼쪽 아래 버전이 최신이면 돌고 있는 코드는 최신이 맞습니다 |

**403 해결 순서**

```bash
# 1. 키체인의 낡은 값 삭제
printf "protocol=https\nhost=github.com\n\n" | git credential-osxkeychain erase

# 2. GitHub Settings → Developer settings → Personal access tokens
#    classic + repo 권한  (또는 fine-grained + 이 저장소의 Contents: Read and write)

# 3. 다시 push — 비밀번호 칸에 토큰을 붙여넣습니다
git push origin main

# 4. 사용자 이름을 매번 치지 않으려면
git config credential.https://github.com.username <아이디>
```

---

## 6. 자주 하는 실수

| 실수 | 증상 | 막는 장치 |
|---|---|---|
| `catalog.py`만 고치고 `Datasets.java`를 안 고침 | 수집은 되는데 화면에 안 보임 | `DatasetsParityTest` |
| 표본이 부족한데 0을 돌려줌 | 화면에 "0.00%"가 보합으로 읽힘 | 계산 테스트 |
| 운영 DB로 테스트 실행 | 수집한 데이터가 전부 사라짐 | `guardAgainstRealDatabase()` |
| `LocalDate.now()` 사용 | KST 00~09시에 날짜가 하루 어긋남 | `Kst.today()`를 쓰세요 |
| 화면에서 직접 외부 API 호출 | 오프라인에서 깨지고 출처 추적 불가 | 백엔드를 거치세요 |
| 값 배열과 날짜 배열을 따로 필터링 | 인덱스가 밀려 조용히 틀린 수익률 | `SectorSeriesAlignmentTest` |
