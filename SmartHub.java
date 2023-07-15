import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Stream;

public class SmartHub {
    private static Long hubSerial = 0L;
    private static Long hubAddress;
    private static String serverURL;
    private static Timestamp time;
    private static Devs.Clock clock;
    private static final long waitTime = 300;
    private static final Map<Integer, Devs.BlankDev> srcToDev = new HashMap<>();
    private static final Map<String, Devs.BlankDev> nameToDev = new HashMap<>();
    private static final Set<Devs.Switch> switches = new HashSet<>();
    private static final Set<Devs.LampSocket> lampsSockets = new HashSet<>();
    private static byte[] iterRequest;

    public static class Utils {
        public static byte computeCRC8(byte[] bytes) {
            final byte generator = 0x1D;
            byte crc = 0; /* start with 0 so first byte can be 'xored' in */

            for (byte currByte : bytes) {
                crc ^= currByte; /* XOR-in the next input byte */
                for (int i = 0; i < 8; i++) {
                    if ((crc & 0x80) != 0) {
                        crc = (byte) ((crc << 1) ^ generator);
                    } else {
                        crc <<= 1;
                    }
                }
            }
            return crc;
        }

        public static class ULED128 {
            public static class DecodeResult {
                final Long result;
                final Integer to;

                public DecodeResult(Long result, Integer to) {
                    this.result = result;
                    this.to = to;
                }
            }

            public static byte[] encodeULED128(Long value) {
                List<Byte> ls = new ArrayList<>();

                do {
                    byte bt = (byte) (value % (1 << 8));
                    value >>= 7;
                    if (value != 0) /* more bytes to come */
                        bt |= (1 << 7);
                    ls.add(bt);
                } while (value != 0);
                byte[] result = new byte[ls.size()];
                for (int i = 0; i < ls.size(); i++) {
                    result[i] = ls.get(i);
                }
                return result;
            }

            public static DecodeResult decodeULED128(byte[] source, int from) {
                long result = 0L;
                long shift = 0L;
                int i = from;
                while (i < source.length) {
                    byte bt = source[i];
                    result |= (Byte.toUnsignedLong(source[i]) & ~(1 << 7)) << shift;
                    if ((bt >> 7) % 2 == 0) {
                        break;
                    }
                    shift += 7;
                    i++;
                }
                return new DecodeResult(result, i);
            }
        }

        public static String decodeString(byte[] string) {
            return new String(Arrays.copyOfRange(string, 1, string.length));
        }

        public static byte[] encodeString(String string) {
            string = (char) ((byte) string.length()) + string;
            return string.getBytes();
        }

        public static byte[] concatByteArrays(byte[]... args) {
            int len = 0;
            for (byte[] arg : args) {
                len += arg.length;
            }
            byte[] result = new byte[len];
            int i = 0;
            for (byte[] arg : args) {
                for (byte j : arg) {
                    result[i++] = j;
                }
            }
            return result;
        }
    }

    public static class FabricPakets {
        public static class Hub {
            protected static byte[] encodeBlank(long dst, Paket.Payload.DevType devType, Paket.Payload.Cmd cmd, byte[] body) {
                Paket.Payload payload =
                        new Paket.Payload(hubAddress,
                                dst,
                                ++hubSerial,
                                devType,
                                cmd,
                                body);
                return Paket.encodePackage(payload);
            }

            private static byte[] encodeBroadCast(Paket.Payload.Cmd cmd) {
                Paket.Payload payload =
                        new Paket.Payload(hubAddress,
                                0x3FFFL,
                                ++hubSerial,
                                Paket.Payload.DevType.SMARTHUB,
                                cmd,
                                Utils.encodeString("SmartHub"));
                return Paket.encodePackage(payload);
            }

            public static byte[] encodeWhoIsHere() {
                return encodeBroadCast(Paket.Payload.Cmd.WHOISHERE);
            }

            public static byte[] encodeIAmHere() {
                return encodeBroadCast(Paket.Payload.Cmd.IAMHERE);
            }

            public static byte[] encodeGetStatus(long dst, Paket.Payload.DevType devType) {
                Paket.Payload payload =
                        new Paket.Payload(hubAddress,
                                dst,
                                ++hubSerial,
                                devType,
                                Paket.Payload.Cmd.GETSTATUS,
                                new byte[]{});
                return Paket.encodePackage(payload);
            }
        }

        public static class OneWayDev {
            public static int decodeStatus(byte[] body) {
                return Byte.toUnsignedInt(body[0]);
            }

            public static String decodeOneWayDevGeneral(byte[] body) {
                return Utils.decodeString(body);
            }

            public static byte[] encodeSetStatus(long dst, Paket.Payload.DevType devType, int value) {
                return Hub.encodeBlank(dst, devType, Paket.Payload.Cmd.SETSTATUS, new byte[]{(byte) value});
            }
        }

        public static class Switch {
            public static class SwitchBody {
                final String name;
                final List<String> devs;

                public SwitchBody(String name, List<String> devs) {
                    this.name = name;
                    this.devs = devs;
                }

                @Override
                public String toString() {
                    return "SwitchBody{" +
                            "name='" + name + '\'' +
                            ", devs=" + devs +
                            '}';
                }
            }

            public static SwitchBody decodeSwitchGeneral(byte[] body) {
                int i = 0;
                String name = null;
                List<String> devs = new ArrayList<>();
                while (i < body.length) {
                    String str = Utils
                            .decodeString(Arrays.copyOfRange(body, i, i + Byte.toUnsignedInt(body[i]) + 1));
                    if (i == 0) {
                        name = str;
                        i += Byte.toUnsignedInt(body[i]) + 2;
                        continue;
                    } else {
                        devs.add(str);
                    }
                    i += Byte.toUnsignedInt(body[i]) + 1;
                }
                return new SwitchBody(name, devs);
            }

            public static int decodeSwitchStatus(byte[] body) {
                return OneWayDev.decodeStatus(body);
            }
        }

        public static class Clock {
            public static Timestamp decodeClockTick(byte[] body) {
                return new Timestamp(Utils.ULED128.decodeULED128(body, 0).result);
            }

            public static String decodeClockGeneral(byte[] body) {
                return OneWayDev.decodeOneWayDevGeneral(body);
            }
        }
    }

    public static class Paket {
        public static class Payload {
            public enum DevType {
                SMARTHUB, ENVSENSOR, SWITCH, LAMP, SOCKET, CLOCK
            }

            public enum Cmd {
                WHOISHERE, IAMHERE, GETSTATUS, STATUS, SETSTATUS, TICK
            }

            final int src;
            final int dst;
            final long serial;
            final DevType devType;
            final Cmd cmd;
            final byte[] cmdBody;

            public Payload(int src, int dst, long serial, byte devType, byte cmd, byte[] cmdBody) {
                this.src = src;
                this.dst = dst;
                this.serial = serial;
                this.devType = DevType.values()[devType - 1];
                this.cmd = Cmd.values()[cmd - 1];
                this.cmdBody = cmdBody;
            }

            public Payload(Long src, Long dst, Long serial, DevType devType, Cmd cmd, byte[] cmdBody) {
                this.src = Math.toIntExact(src);
                this.dst = Math.toIntExact(dst);
                this.serial = serial;
                this.devType = devType;
                this.cmd = cmd;
                this.cmdBody = cmdBody;
            }

            public static byte[] encodePayload(Payload payload) {
                return Utils.concatByteArrays(
                        Utils.ULED128.encodeULED128((long) payload.src),
                        Utils.ULED128.encodeULED128((long) payload.dst),
                        Utils.ULED128.encodeULED128(payload.serial),
                        new byte[]{(byte) (payload.devType.ordinal() + 1)},
                        new byte[]{(byte) (payload.cmd.ordinal() + 1)},
                        payload.cmdBody
                );
            }

            public static Payload decodePayload(byte[] payload) {
                Utils.ULED128.DecodeResult decodeResult = Utils.ULED128.decodeULED128(payload, 0);
                int src = Math.toIntExact(decodeResult.result);
                decodeResult = Utils.ULED128.decodeULED128(payload, decodeResult.to + 1);
                int dst = Math.toIntExact(decodeResult.result);
                decodeResult = Utils.ULED128.decodeULED128(payload, decodeResult.to + 1);
                long serial = decodeResult.result;
                int point = decodeResult.to;
                byte devType = payload[++point];
                byte cmd = payload[++point];
                return new Payload(src, dst, serial, devType, cmd,
                        Arrays.copyOfRange(payload, ++point, payload.length));
            }

            @Override
            public String toString() {
                return "payload={" +
                        "src:" + src +
                        ", dst:" + dst +
                        //", \"serial\":" + serial +
                        ", devType:" + devType +
                        ", cmd:" + cmd +
                        ", cmdBody:" + Arrays.toString(cmdBody) +
                        "}";
            }
        }

        final int length;
        final Payload payload;
        final byte crc8;

        public Paket(byte length, byte[] payload, byte crc8) {
            this.length = Byte.toUnsignedInt(length);
            this.payload = Payload.decodePayload(payload);
            this.crc8 = crc8;
        }

        public static byte[] encodePackage(Payload payload) {
            byte[] payloadByte = Payload.encodePayload(payload);
            return Utils.concatByteArrays(
                    new byte[]{(byte) payloadByte.length},
                    payloadByte,
                    new byte[]{Utils.computeCRC8(payloadByte)}
            );
        }

        public static Paket decodePackage(byte[] paket) {
            return new Paket(
                    paket[0],
                    Arrays.copyOfRange(paket, 1, paket.length - 1),
                    paket[paket.length - 1]
            );
        }

        @Override
        public String toString() {
            return "Paket{" +
                    "length=" + length +
                    ", " + payload +
                    ", crc8=" + crc8 +
                    '}';
        }
    }

    public static class Devs {
        public static abstract class BlankDev {
            int src;
            String name;
            Paket.Payload.DevType devType;

            public BlankDev(int src, String name, Paket.Payload.DevType devType) {
                this.src = src;
                this.name = name;
                this.devType = devType;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                BlankDev blankDev = (BlankDev) o;
                return src == blankDev.src && Objects.equals(name, blankDev.name) && devType == blankDev.devType;
            }

            @Override
            public int hashCode() {
                return Objects.hash(src, name, devType);
            }

            @Override
            public String toString() {
                return "BlankDev{" +
                        "src=" + src +
                        ", name='" + name + '\'' +
                        ", devType=" + devType +
                        '}';
            }
        }

        public static class Switch extends BlankDev {
            List<String> list;

            public Switch(int src, String name, List<String> list) {
                super(src, name, Paket.Payload.DevType.SWITCH);
                this.list = list;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Switch aSwitch = (Switch) o;
                return Objects.equals(list, aSwitch.list)
                        && aSwitch.src == src
                        && Objects.equals(aSwitch.name, name)
                        && aSwitch.devType == devType;
            }

            @Override
            public String toString() {
                return "Switch{" +
                        "list=" + list +
                        ", src=" + src +
                        ", name='" + name + '\'' +
                        ", devType=" + devType +
                        '}';
            }
        }

        public static class LampSocket extends BlankDev {
            public LampSocket(int src, String name, Paket.Payload.DevType devType) {
                super(src, name, devType);
            }

            @Override
            public String toString() {
                return "LampSocket{" +
                        "src=" + src +
                        ", name='" + name + '\'' +
                        ", devType=" + devType +
                        '}';
            }
        }

        public static class Clock extends BlankDev {
            public Clock(int src, String name) {
                super(src, name, Paket.Payload.DevType.CLOCK);
            }
        }
    }

    public static Set<Paket> getSetPakets(String string) {
        byte[] response = Base64.getUrlDecoder().decode(string);
        int i = 0;
        Set<Paket> pakets = new HashSet<>();
        while (i < response.length) {
            Paket paket = Paket.decodePackage(Arrays.copyOfRange(response, i, i + Byte.toUnsignedInt(response[i]) + 2));
            pakets.add(paket);
            i += Byte.toUnsignedInt(response[i]) + 2;
        }
        return pakets;
    }

    public static void updateClock(Set<Paket> pakets) {
        for (Paket paket : pakets) {
            if (paket.payload.cmd == Paket.Payload.Cmd.TICK) {
                time = FabricPakets.Clock.decodeClockTick(paket.payload.cmdBody);
                pakets.removeIf((paketTick -> paketTick.payload.cmd == Paket.Payload.Cmd.TICK));
                return;
            }
        }
    }

    public static void updateDevicesMaps(Devs.BlankDev dev, Paket.Payload.DevType devType) {
        switch (devType) {
            case SWITCH -> switches.add((Devs.Switch) dev);
            case LAMP, SOCKET -> lampsSockets.add((Devs.LampSocket) dev);
        }
        srcToDev.put(dev.src, dev);
        nameToDev.put(dev.name, dev);
    }

    public static void addRequest(byte[] request) {
        iterRequest = Utils.concatByteArrays(iterRequest, request);
    }

    public static void updateDevicesHere(Set<Paket> pakets) {
        Set<Paket> removePakets = new HashSet<>();
        for (Paket paket : pakets) {
            Paket.Payload payload = paket.payload;
            if (payload.cmd == Paket.Payload.Cmd.IAMHERE || payload.cmd == Paket.Payload.Cmd.WHOISHERE) {
                if (payload.cmd == Paket.Payload.Cmd.WHOISHERE) {
                    addRequest(FabricPakets.Hub.encodeIAmHere());
                }
                removePakets.add(paket);
                switch (payload.devType) {
                    case SWITCH -> {
                        FabricPakets.Switch.SwitchBody body = FabricPakets.Switch.decodeSwitchGeneral(payload.cmdBody);
                        Devs.Switch newSwitch = new Devs.Switch(payload.src, body.name, body.devs);
                        updateDevicesMaps(newSwitch, payload.devType);
                    }
                    case LAMP, SOCKET -> {
                        String name = FabricPakets.OneWayDev.decodeOneWayDevGeneral(payload.cmdBody);
                        Devs.LampSocket newLampSocket = new Devs.LampSocket(payload.src, name, payload.devType);
                        updateDevicesMaps(newLampSocket, payload.devType);
                    }
                    case CLOCK ->
                            clock = new Devs.Clock(payload.src, FabricPakets.Clock.decodeClockGeneral(payload.cmdBody));
                }
            }
        }
        pakets.removeAll(removePakets);
    }

    private static class SmartHubExceptions {
        private static class EndSessionException extends RuntimeException {
            public EndSessionException(String message) {
                super(message);
            }
        }
        private static class ErrorServerException extends Error {
            public ErrorServerException(String message) {
                super(message);
            }
        }

    }

    private static HttpResponse<String> doRequest() throws SmartHubExceptions.ErrorServerException,
            SmartHubExceptions.EndSessionException, IOException, InterruptedException, URISyntaxException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(serverURL))
                .POST(HttpRequest.BodyPublishers.ofString(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(iterRequest))
                )
                .build();
        iterRequest = new byte[0];
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) {
            throw new SmartHubExceptions.EndSessionException("End of session.");
        }
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new SmartHubExceptions.ErrorServerException("Error server, status code: " + response.statusCode());
        }
        return response;
    }

    private static void dispatcher(Set<Paket> pakets) {
        Set<Paket> removePakets = new HashSet<>();
        for (Paket paket : pakets) {
            Paket.Payload payload = paket.payload;
            //System.out.println(payload);
            switch (payload.devType) {
                case SWITCH -> {
                    if (payload.cmd == Paket.Payload.Cmd.STATUS) {
                        removePakets.add(paket);
                        Devs.Switch swt = (Devs.Switch) srcToDev.get(payload.src);
                        for (String devs : swt.list) {
                            Devs.LampSocket lampSocket = (Devs.LampSocket) nameToDev.get(devs);
                            addRequest(
                                    FabricPakets.OneWayDev.encodeSetStatus(
                                            lampSocket.src,
                                            lampSocket.devType,
                                            FabricPakets.Switch.decodeSwitchStatus(payload.cmdBody))
                            );
                        }
                    }
                }
                case LAMP, SOCKET -> {
                    if (payload.cmd == Paket.Payload.Cmd.STATUS) {
                        removePakets.add(paket);
                    }
                }
            }
        }
        pakets.removeAll(removePakets);
    }

    public static void main(String[] args) {
        serverURL = args[0];
        hubAddress = Long.parseLong(args[1], 16);
        iterRequest = new byte[0];
        HttpResponse<String> response = null;


        addRequest(FabricPakets.Hub.encodeWhoIsHere());
        try {
            response = doRequest();
        } catch (SmartHubExceptions.ErrorServerException | IOException | InterruptedException | URISyntaxException e) {
            System.exit(99);
        } catch (SmartHubExceptions.EndSessionException e) {
            System.exit(0);
        }
        Set<Paket> pakets = getSetPakets(response.body());
        updateClock(pakets);
        Timestamp next_exit = new Timestamp(time.getTime() + waitTime);
        updateDevicesHere(pakets);

        while (!time.after(next_exit)) {
            try {
                response = doRequest();
            } catch (SmartHubExceptions.ErrorServerException | IOException | InterruptedException | URISyntaxException e) {
                System.exit(99);
            } catch (SmartHubExceptions.EndSessionException e) {
                System.exit(0);
            }
            pakets = getSetPakets(response.body());
            updateClock(pakets);
            updateDevicesHere(pakets);
            System.out.println("Ostatok: " + pakets + " lost time: " + (next_exit.getTime() - time.getTime()));
        }
        for (Devs.BlankDev dev : Stream.concat(
                switches.stream().map(it -> ((Devs.BlankDev) it)),
                lampsSockets.stream().map(it -> ((Devs.BlankDev) it))).toList()) {
            addRequest(FabricPakets.Hub.encodeGetStatus(dev.src, dev.devType));
        }
        for (int it = 0; true; it++){
            try {
                response = doRequest();
            } catch (SmartHubExceptions.ErrorServerException | IOException | InterruptedException | URISyntaxException e) {
                System.exit(99);
            } catch (SmartHubExceptions.EndSessionException e) {
                System.exit(0);
            }
            pakets = getSetPakets(response.body());
            updateClock(pakets);
            updateDevicesHere(pakets);
            dispatcher(pakets);
            if(it % 10000 == 0) {
                System.out.println(srcToDev);
                System.out.println(nameToDev);
                System.out.println(switches);
                System.out.println(lampsSockets);
            }
        }

    }
}
