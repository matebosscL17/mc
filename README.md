# SMPCore 1.1

Paper 1.21.x plugin SMP szerverhez (Java 21).

## Funkciók
- **Sírkő:** halálkor a cuccaid (és az XP) egy játékos-fej blokkba kerülnek a halál helyén. Jobb klikk = kinyitás. Az első 5 percben csak a tulajdonos nyithatja. `/graves` listázza a sírjaidat.
- **Gazdaság:** `/balance [játékos]`, `/pay`, `/baltop`, `/eco give|take|set` (admin)
- **Shop:** `/shop` (GUI, bal klikk = vétel, jobb klikk = eladás, shift = 16 db), `/sell` (kézben lévő), `/sell all`
- **Fejpénz:** `/bounty [játékos] [összeg]`, `/bounty list`; aki megöli a játékost, megkapja
- **Napi jutalom:** `/daily`, egymás utáni napokra nő
- **Toplisták:** `/top [playtime|kills|deaths|balance]`
- Halál koordináták + `/death`, PvP fejek, belépő/kilépő üzenetek, üdvözlő title, `/rules`, `/discord`
- `/smpcore reload` újratölti a configot (jog: `smpcore.admin`)

Minden szöveg, ár és funkció a `plugins/SMPCore/config.yml`-ben állítható.

## Fontos
- A gazdaság **önálló, nem Vault-kompatibilis**. Más plugin nem látja az egyenleget.
- **EssentialsX mellett** ütköznek a parancsok (`/balance`, `/pay`, `/eco`, `/sell`, `/baltop`). Az EssentialsX `config.yml`-ben tiltsd le őket:
  `disabled-commands: [balance, bal, money, pay, eco, baltop, balancetop, sell, worth]`
- Az adatok a `plugins/SMPCore/` mappában vannak (balances.yml, graves.yml, bounties.yml, daily.yml, stats.yml). Ezeket ne töröld, ha meg akarod tartani a pénzt és a sírokat.

## Lefordítás (nem kell semmit telepíteni)
1. Csinálj egy GitHub repót, és töltsd fel ennek a mappának a tartalmát.
2. Az **Actions** fülön futtasd a **Build** workflow-t (az első feltöltésnél magától elindul).
3. A lefutott workflow aljáról töltsd le az **SMPCore** artifactot, csomagold ki: benne van az `SMPCore.jar`.

Saját gépen: JDK 21 + Maven, majd `mvn package` -> `target/SMPCore.jar`.

## Aternos
Files fül -> `plugins` mappa -> feltöltöd az `SMPCore.jar`-t -> szerver újraindítás. A Discord linket a `config.yml`-ben írd át.
