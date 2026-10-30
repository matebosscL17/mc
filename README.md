# SMPCore 1.1

Paper 26.x plugin SMP szerverhez (Java 25). A kliens és a szerver Minecraft verziója egyezzen (pl. mindkettő 26.3).

## Funkciók
- **Sírkő:** halálkor a cuccaid (és az XP) egy játékos-fej blokkba kerülnek a halál helyén. Jobb klikk = kinyitás. Az első 5 percben csak a tulajdonos nyithatja. `/graves` listázza a sírjaidat.
- **Gazdaság:** `/balance [játékos]`, `/pay`, `/baltop`, `/eco give|take|set` (admin)
- **Shop:** `/shop` (GUI, bal klikk = vétel, jobb klikk = eladás, shift = 16 db), `/sell` (kézben lévő), `/sell all`
- **Fejpénz:** `/bounty [játékos] [összeg]`, `/bounty list`; aki megöli a játékost, megkapja
- **Napi jutalom:** `/daily`, egymás utáni napokra nő
- **Foglalkozások:** bányász, farmer, vadász. Ércekért, érett terményért és mobölésért pénz jár, szintenként nő. `/jobs` mutatja a szinteket.
- **Aukciósház:** `/ah` (lapozható GUI, kattintásra vétel), `/ah sell [ár]` (kézben lévő tárgy), `/ah my` (saját hirdetések, visszavonás). Eladáskor 5% adó.
- **Shop kategóriákkal:** `/shop` főmenü, minden kategória lapozható.
- **Lobby:** `/lobby build` egy Penthouse stílusú lebegő platformot épít (sakktábla padló, szökőkút, üvegkorlát, arany oszlopok), ez lesz a spawn. `/lobby` bárkit visszavisz, `/lobby set` saját építésű helyet állít be. Az arany nyomólapról véletlen teleporttal indulnak a vadonba (`/rtp` parancsként is megy, `/wild`).
- **Kezdőcsomag** első belépéskor, **védett spawn** (építés és PvP tiltva, admin kivétel).
- **AFK:** 5 perc tétlenség után AFK jelzés a tablistán, `/afk` kézzel is.
- **Automatikus mentés:** 2 óránként zip a szerver `backups` mappájába (10 darabot tart meg), `/backup` azonnal.
- **Sidebar scoreboard:** oldalt a szerver neve, pénz, ölés/halál, játszott idő, online létszám. `/sidebar` kapcsolja ki-be. A szerver nevét a config `sidebar.title` sorában írd át.
- **Toplisták:** `/top [playtime|kills|deaths|balance]`
- Halál koordináták + `/death`, PvP fejek, belépő/kilépő üzenetek, üdvözlő title, `/rules`, `/discord`
- `/smpcore reload` újratölti a configot (jog: `smpcore.admin`)

Minden szöveg, ár és funkció a `plugins/SMPCore/config.yml`-ben állítható.

## Fontos
- A gazdaság **önálló, nem Vault-kompatibilis**. Más plugin nem látja az egyenleget.
- **EssentialsX mellett** ütköznek a parancsok (`/balance`, `/pay`, `/eco`, `/sell`, `/baltop`). Az EssentialsX `config.yml`-ben tiltsd le őket:
  `disabled-commands: [balance, bal, money, pay, eco, baltop, balancetop, sell, worth]`
- Az adatok a `plugins/SMPCore/` mappában vannak (balances.yml, graves.yml, bounties.yml, daily.yml, stats.yml). Ezeket ne töröld, ha meg akarod tartani a pénzt és a sírokat.

## Letöltés
A kész jar mindig itt van (minden feltöltés után magától frissül):
https://github.com/matebosscL17/mc/releases/download/latest/SMPCore.jar

## Szerver
1. Java 25 (Temurin JDK 25 az adoptium.net oldalról), ellenőrzés: `java -version`
2. Paper 26.3: https://papermc.io/downloads/paper
3. Az `SMPCore.jar`-t told a szerver `plugins` mappájába, majd indítsd újra a szervert.
4. A `plugins/SMPCore/config.yml`-ben írd át a szerver nevét (`sidebar.title`) és a Discord linket.

Az **Aternos nem engedi** saját plugin jar feltöltését, ott az SMPCore nem fut.

## Saját fordítás
JDK 25 + Maven, majd `mvn package` -> `target/SMPCore.jar`. A GitHub Actions is lefordítja minden feltöltéskor.
