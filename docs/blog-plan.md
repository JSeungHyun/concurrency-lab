# 블로그 게시글

**한 편짜리 글**로 쓴다. 전략마다 어떤 방법이고 어떤 결과가 나왔는지를 캡처와 함께 보여 준다.

- 본문 템플릿: [post/template.md](post/template.md). 문장은 여기서 고친다.
- 수치·표·이미지는 손으로 쓰지 않는다. `{{...}}` 자리표시자를 스크립트가 채운다.

## 만드는 순서

```powershell
# 1. 재고 부족 상황 (기본: 쿠폰 100장 / 요청 3000건) — 끝나면 RUN_ID 가 출력된다
.\scripts\run-all.ps1

# 2. 재고 충분 상황 (쿠폰 수량 = 요청 수)
.\scripts\run-all.ps1 -Quantity 3000

# 3. 두 실행의 RUN_ID 로 게시글 생성
.\scripts\build-post.ps1 -Runs @{ burst = '<1의 RUN_ID>'; write = '<2의 RUN_ID>' } -Name final
```

결과: `results/post/final/post.md` 와 `images/`. 이 폴더를 블로그 편집기에 옮긴다.

## 글 쓸 때 주의할 점

- **전략 간 순위를 문장에 고정하지 않는다.** 실행마다 바뀔 수 있다. 순위를 말하려면 `-Repeat 3` 결과로 확인한다.
- **재고 부족 / 재고 충분 상황을 반드시 구분한다.** 재고 부족 상황은 97%가 매진 응답이라 사실상 "매진을 얼마나 빨리 돌려주느냐"를 잰 것이다. 빌드 스크립트 라벨은 `burst`(재고 부족), `write`(재고 충분)다.
- **환경을 적는다.** 단일 PC, Docker Desktop, 앱 CPU 2 / 힙 512m, 커넥션 풀 20, Tomcat 스레드 200.
- **서버 1대 실험이다.** synchronized 가 통과한 건 이 때문이다.
