# Despliegue en las tres máquinas

Guía paso a paso para poner a correr el chat distribuido en la topología real:
servidor Java en una VM Debian, cliente Python en una VM Windows, cliente Swift
en el Mac anfitrión. Ver también `PROTOCOL.md` y `CLAUDE-2.md` sección 16.

```text
Mac anfitrión
├── Cliente macOS en Swift   (Cliente2)
├── VM Debian                (ServerChat)      <-- servidor
└── VM Windows                (Clienter1)      <-- cliente Python
```

Los tres deben quedar en la **misma red virtual** para poder verse por IP. Sigue el
orden: 1) red, 2) servidor Debian, 3) verificar puerto, 4) cliente Windows,
5) cliente macOS.

---

## 0. Configurar la red de las VMs (antes que nada)

Cada hipervisor le llama distinto a lo mismo. Busca la opción para poner el
adaptador de red de **ambas VMs** (Debian y Windows) en modo:

- **"Red interna" / "Host-only" / "Shared network"** — las VMs y el Mac anfitrión
  se ven entre sí con IPs privadas, sin salir a internet por esa interfaz.
- Si además quieres que las VMs tengan internet (para instalar paquetes), déjales
  **una segunda interfaz en modo NAT/Bridged** — no la uses para el chat, solo para
  actualizar/instalar software.

Dónde se configura según el hipervisor:

| Hipervisor | Dónde |
|---|---|
| UTM | Config de la VM → Network → agrega un adaptador "Shared Network" o "Host Only" |
| VMware Fusion | Config de la VM → Network Adapter → "Private to my Mac" (host-only) |
| Parallels Desktop | Config de la VM → Hardware → Network → "Host-Only" |
| VirtualBox | Config de la VM → Red → Adaptador 2 → "Red solo-anfitrión" |

Después de arrancar las VMs con esto configurado, cada una tendrá una IP en ese
rango privado (normalmente `192.168.x.x` o `10.x.x.x`). Esa es la IP que vas a usar
para conectarte, **no** `127.0.0.1` (esa solo sirve para pruebas dentro de la misma
máquina).

---

## 1. VM Debian — servidor (`ServerChat`)

### 1.1 Instalar Java

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk-headless
java -version   # confirma que quedó instalado
```

`openjdk-17-jdk-headless` alcanza (no hace falta la variante con GUI, el servidor
no tiene interfaz gráfica). El código ya se verificó que compila limpio con
`--release 17`.

### 1.2 Pasar el código a la VM

Cualquiera de estas opciones funciona; usa la que tengas más a la mano:

**Opción A — `scp` desde el Mac** (si la VM tiene SSH activo: `sudo apt install -y openssh-server`):
```bash
# desde el Mac, apuntando a la carpeta ServerChat
scp -r "ServerChat" usuario@<ip-vm-debian>:~/
```

**Opción B — carpeta compartida** del hipervisor (UTM/VMware/Parallels/VirtualBox
todos tienen "shared folder"): actívala en la configuración de la VM y copia
`ServerChat/` ahí.

**Opción C — `git`**, si ya tienes el proyecto en un repositorio remoto:
```bash
git clone <tu-repo> && cd <tu-repo>/ServerChat
```

### 1.3 Compilar y ejecutar

```bash
cd ServerChat
javac -d out $(find src -name "*.java")
java -cp out Main 5000
```

`5000` es el puerto — puedes usar otro si lo prefieres (ver 1.5). Déjalo corriendo
en esa terminal mientras haces las pruebas; vas a ver logs de conexión/registro
ahí mismo. Para no depender de que la terminal quede abierta:

```bash
nohup java -cp out Main 5000 > server.log 2>&1 &
```

### 1.4 Abrir el puerto en el firewall

Debian normalmente **no trae firewall activo por defecto**. Verifica así:

```bash
sudo ufw status
```

- Si dice `Status: inactive` → no tienes que hacer nada más, el puerto ya está
  abierto.
- Si dice `Status: active` → abre el puerto explícitamente:
  ```bash
  sudo ufw allow 5000/tcp
  sudo ufw reload
  ```

### 1.5 Anota la IP de esta VM

```bash
hostname -I
```

Vas a necesitar esa IP (algo como `192.168.64.5`) en los dos clientes.

---

## 2. Verificar que el puerto realmente quedó accesible

Desde **otra máquina** (el Mac, o la VM Windows), antes de abrir cualquier
cliente gráfico:

**Desde el Mac o Linux:**
```bash
ping <ip-vm-debian>              # confirma que hay red
nc -vz <ip-vm-debian> 5000       # confirma que el puerto responde
```
Si `nc` dice `succeeded!` o `open`, el servidor es alcanzable. Si da timeout,
revisa el paso 0 (red) y el paso 1.4 (firewall).

**Desde Windows (PowerShell):**
```powershell
Test-Connection <ip-vm-debian>                      # equivalente a ping
Test-NetConnection -ComputerName <ip-vm-debian> -Port 5000
```
`TcpTestSucceeded : True` significa que el puerto está bien.

---

## 3. VM Windows — cliente Python (`Clienter1`)

### 3.1 Instalar Python

Descarga el instalador oficial desde python.org (3.9 o más nuevo) e instálalo.
**Importante:** en el instalador, deja marcada la opción de instalar
**tcl/tk and IDLE** (viene marcada por defecto) — sin eso no funciona Tkinter,
que es lo que usa la interfaz gráfica de este cliente.

Verifica en `cmd` o PowerShell:
```powershell
python --version
python -m tkinter    # si abre una ventanita de prueba, Tkinter esta bien
```

### 3.2 Pasar el código a la VM

Mismas opciones que en 1.2 (carpeta compartida del hipervisor es la más simple en
Windows; también puedes usar WinSCP si activaste SSH en el Mac/Debian, o `git`).

### 3.3 Ejecutar apuntando al servidor Debian

El cliente **ya no pregunta servidor ni puerto** — hay que pasarlos por variable
de entorno antes de correrlo, apuntando a la IP que anotaste en el paso 1.5:

**PowerShell:**
```powershell
$env:CHAT_HOST = "<ip-vm-debian>"
$env:CHAT_PORT = "5000"
cd Clienter1\src
python main.py
```

**cmd:**
```cmd
set CHAT_HOST=<ip-vm-debian>
set CHAT_PORT=5000
cd Clienter1\src
python main.py
```

Si Windows Firewall pregunta si permitir que `python.exe` acceda a redes
privadas/públicas, dale **Permitir** — si no, el cliente no va a poder conectar
aunque el servidor esté bien.

---

## 4. Mac anfitrión — cliente macOS (`Cliente2`)

Este corre directo en el Mac, no en una VM. Como está en modo host-only/shared,
el Mac anfitrión ya puede ver esa red sin configuración extra.

```bash
cd Cliente2
CHAT_HOST=<ip-vm-debian> CHAT_PORT=5000 swift run
```

Escribe el nombre de usuario y conecta. Si prefieres no escribir las variables
cada vez, expórtalas una vez por sesión de terminal:

```bash
export CHAT_HOST=<ip-vm-debian>
export CHAT_PORT=5000
swift run
```

---

## 5. Checklist rápido si algo no conecta

1. `ping` entre las máquinas funciona → si no, el problema es de red (paso 0),
   no del chat.
2. El servidor está corriendo y su terminal no mostró ningún error al arrancar.
3. `nc -vz`/`Test-NetConnection` al puerto del servidor da éxito.
4. Los clientes usan la **IP real de la VM Debian**, no `127.0.0.1` (esa solo
   sirve si el cliente corre en la misma máquina que el servidor).
5. En Windows, el firewall no está bloqueando `python.exe`.
6. En macOS, si vas a correr el servidor *en el propio Mac* para una prueba
   rápida (no en la VM), recuerda que el puerto **5000 lo ocupa AirPlay
   Receiver de macOS** — usa otro puerto (ver `ServerChat/Readme.md`) o
   desactívalo en Ajustes del Sistema → General → AirDrop y Handoff.
