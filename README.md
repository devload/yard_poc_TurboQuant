# TurboQuant for WhaTap YARD

> Google TurboQuant (ICLR 2026) 벡터 양자화 알고리즘을 WhaTap YARD 모니터링 서버에 적용하는 PoC

📄 **[인터랙티브 HTML 리포트 (report.html)](report.html)** — 차트 포함 전체 결과 보기

---

## PoC 개요

**TurboQuant**은 Google Research가 ICLR 2026에서 발표한 벡터 양자화 알고리즘입니다. 원래 LLM KV Cache 압축용으로 설계되었으나, 이 PoC에서는 WhaTap YARD 서버의 **시계열 데이터 압축**, **유사도 검색**, **이상 탐지** 3가지 관점에서 효용성을 검증합니다.

### 왜 TurboQuant인가?

- **학습 불필요**: 데이터 의존 없이 즉시 적용 가능 (training-free)
- **극한 압축**: 3-bit으로 8.57x 압축, 정보 손실 최소화
- **내적 보존**: 압축 상태에서 직접 유사도 계산 가능 (QJL 보정)
- **범용성**: LLM뿐 아니라 모든 고차원 벡터 데이터에 적용 가능

---

## 핵심 결과 요약

| 지표 | 결과 |
|------|------|
| 최고 압축률 (3-bit) | **8.57x** |
| 이상 탐지 재현율 | **100%** (3/3) |
| 이상 탐지 F1 점수 | **75%** |
| 클러스터 정확도 (QJL=128) | **59.6%** |
| 벡터당 크기 (480차원) | **248 bytes** |
| 캐싱 속도 향상 | **4.5x** |

---

## PoC 6단계 로드맵

| Phase | 작업 | 핵심 결과 |
|-------|------|----------|
| **1** | YARD 데모 생성기 | WhaTap 바이너리 프로토콜 구현, 4가지 메트릭 패턴, fat JAR |
| **2** | 시계열 압축 벤치마크 | 3-bit **8.57x**, 4-bit **6.97x** (GZIP 1.10x 대비) |
| **3** | 벡터 유사도 검색 | 480차원 벡터, 클러스터 정확도 51.6%, Recall@5 31.6% |
| **4** | 이상 탐지 | Precision 60%, **Recall 100%**, F1 75% |
| **5** | 캐싱 최적화 | RotationCache로 QR 분해 **4.5x** 속도 향상 |
| **6** | QJL 차원 튜닝 | QJL 128으로 클러스터 정확도 **59.6%** (+8p), Recall **38.0%** (+6.4p) |

---

## 시스템 아키텍처

```
데모 생성기 → YARD 서버 → TurboQuant → 벡터 저장소 → 이상 탐지
(VirtualAgent)  (TCP:6610)  (회전+Lloyd-Max)  (4-bit+QJL)   (마할라노비스)
```

## 모듈 구성

```
whatap-turboquant/
├── turboquant-core/      # TurboQuant 알고리즘 (순수 Java, 외부 의존성 없음)
│   ├── TurboQuantizer    # 메인 양자화기 (노름 분리 + 회전 + Lloyd-Max)
│   ├── RandomRotation    # QR 분해 기반 랜덤 직교 회전
│   ├── LloydMaxCodebook  # Beta 분포 최적 코드북 (300회 반복)
│   ├── BitPackedVector   # N-bit 패킹/언패킹
│   ├── QJLProjection     # 1-bit 잔차 프로젝션 (내적 보정)
│   └── RotationCache     # ConcurrentHashMap 기반 회전 행렬 캐싱
├── turboquant-demo/      # YARD 트래픽 생성기
│   ├── DemoMain          # CLI 진입점
│   ├── VirtualAgent      # 가상 에이전트 (13개 메트릭 패턴)
│   ├── YardTcpSender     # NetHead 22B + Pack 바이너리 전송
│   └── PackBuilder       # CounterPack / TagCountPack 생성
├── turboquant-storage/   # 시계열 압축 벤치마크
│   ├── MetricValueCodec  # 필드별 정규화 + TurboQuant 인코딩
│   └── StorageBenchmark  # Raw/GZIP/TQ 비교 + Phase 5 캐싱 벤치마크
├── turboquant-search/    # 벡터 유사도 검색
│   ├── ServerStateVector # 8메트릭 x 60타임스텝 = 480차원
│   ├── CompressedVectorStore # TQ+QJL 압축 벡터 저장소
│   └── SimilaritySearchMain  # Phase 6 QJL 차원 비교 포함
├── turboquant-anomaly/   # 이상 탐지
│   ├── BaselineBuilder   # 히스토리컬 평균/분산 벡터
│   ├── AnomalyDetector   # 마할라노비스 거리 + 적응형 임계값
│   └── EventPackBuilder  # WhaTap 알림 메시지 생성
├── report.html           # 인터랙티브 HTML 벤치마크 리포트
└── benchmark_report.csv  # 압축 벤치마크 CSV 데이터
```

---

## Phase 2: 시계열 압축 벤치마크

> MetricValue(count, sum, min, max, last) × 60 타임스텝 = 300차원 벡터

| 방법 | 원본 | 압축 | 압축률 | MSE(last) |
|------|------|------|--------|-----------|
| Raw | 138,000 | 138,000 | 1.00x | 0 |
| GZIP | 138,000 | 125,416 | 1.10x | 0 |
| **TQ 3-bit** | 138,000 | 16,100 | **8.57x** | 96.1 |
| TQ 3-bit+GZIP | 138,000 | 18,400 | 7.50x | 96.1 |
| **TQ 4-bit** | 138,000 | 19,800 | **6.97x** | 41.1 |
| TQ 4-bit+GZIP | 138,000 | 22,100 | 6.24x | 41.1 |

**해석**: GZIP은 float 바이너리에 거의 무효(1.10x). TurboQuant 3-bit은 **8.57x** 달성. YARD yardbase 일일 데이터 1GB 기준 → **120MB로 축소** (87% 절감).

---

## Phase 4: 이상 탐지 결과

> 20 에이전트, 3종 이상 주입, 마할라노비스 거리 + 적응형 임계값 (mean − 5σ)

| 에이전트 | 이상 유형 | 유사도 | 임계값 | 결과 |
|---------|----------|--------|--------|------|
| 1002 | CPU spike 95% | 0.0777 | 0.4559 | ✅ 정탐 |
| 1007 | 응답시간 10x | 0.0385 | 0.4634 | ✅ 정탐 |
| 1015 | 메모리 급증 + TPS 급감 | 0.0614 | 0.4561 | ✅ 정탐 |

**Confusion Matrix**

|  | 예측: 이상 | 예측: 정상 |
|--|----------|----------|
| **실제 이상** | 3 (TP) | 0 (FN) |
| **실제 정상** | 2 (FP) | 15 (TN) |

> Precision **60%** / Recall **100%** / F1 **75%**

---

## Phase 5: 캐싱 최적화

| 구분 | 초기화 시간 | 비고 |
|------|-----------|------|
| Cold (QR 분해) | 61.7ms | O(n³) 연산 |
| **Warm (캐시 히트)** | **13.8ms** | RotationCache |
| **속도 향상** | **4.5x** | ConcurrentHashMap |

---

## Phase 6: QJL 프로젝션 차원 튜닝

| QJL 차원 | 클러스터 정확도 | Recall@5 | 검색 시간 | 비고 |
|---------|--------------|----------|----------|------|
| 16 | 41.2% | 28.8% | 10.10ms | 부족 |
| 32 | 51.6% | 31.6% | 9.06ms | Phase 3 기본값 |
| 64 | 56.4% | 36.4% | 9.67ms | 양호 |
| **128** | **59.6%** | **38.0%** | 11.28ms | **✅ 최적** |
| 256 | 46.8% | 38.8% | 15.25ms | 과적합 |

> QJL=128에서 클러스터 정확도 +8p, Recall +6.4p 향상. 추가 저장 비용은 벡터당 16바이트(6%)에 불과.

---

## 빌드 & 실행

### 사전 준비

```bash
# whatap.server.common을 로컬 Maven에 설치
cd /path/to/whatap-server/io.whatap
mvn install -pl whatap.server.common -am -DskipTests
```

### 빌드

```bash
cd whatap-turboquant
mvn clean package -DskipTests
```

### 실행

```bash
# Phase 1: Demo Generator (YARD 필요)
java -jar turboquant-demo/target/turboquant-demo.jar \
  --host 127.0.0.1 --port 6610 --pcode 12345 --agents 10 --duration 60

# Phase 2+5: Storage Benchmark (캐싱 포함)
java -cp "turboquant-storage/target/classes:turboquant-core/target/classes:<whatap-common-jar>" \
  io.whatap.turboquant.storage.benchmark.StorageBenchmark

# Phase 3+6: Similarity Search (QJL 차원 비교)
java -cp "<all-modules-classpath>" \
  io.whatap.turboquant.search.api.SimilaritySearchMain

# Phase 4: Anomaly Detection
java -cp "<all-modules-classpath>" \
  io.whatap.turboquant.anomaly.api.AnomalyMain
```

### 테스트

```bash
mvn test -pl turboquant-core  # 5 tests, all pass
```

---

## TurboQuant 알고리즘 요약

| 단계 | 연산 | 목적 |
|------|------|------|
| 1a | 랜덤 직교 회전 (QR 분해) | 좌표에 Beta(d/2, d/2) 분포 유도 |
| 1b | Lloyd-Max 최적 양자화 (300회) | MSE 최적 코드북 |
| 2 | QJL 1-bit 잔차 프로젝션 | 비편향 내적 추정 |
| - | L2 노름 float32 저장 | 벡터 크기 보존 |

**압축 포맷**: `[4B norm] [d × numBits / 8 bytes: packed indices] [optional: QJL signs]`

---

## 참고

- 논문: [TurboQuant: Online Vector Quantization with Near-optimal Distortion Rate](https://arxiv.org/abs/2504.19874)
- 참조 구현: [tonbistudio/turboquant-pytorch](https://github.com/tonbistudio/turboquant-pytorch)
- Google 블로그: [TurboQuant: Redefining AI efficiency](https://research.google/blog/turboquant-redefining-ai-efficiency-with-extreme-compression/)

---

## 기술 스택

- **Java 8** (YARD 호환)
- **Maven** 멀티모듈
- **whatap.server.common** 0.0.1 (Pack, Value, IO 클래스 재사용)
- 외부 라이브러리 없음 (순수 Java 구현)
