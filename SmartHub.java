import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Stream;

class SmartHub {
    protected static Long hubSerial = 0L;
    protected static Long hubAddress;
    protected static final String hubName = "SmartHub";
    protected static String serverURL;
    protected static Timestamp time;
    protected static final long waitTime = 300;
    protected static final long coolDown = 500;
    protected static final Map<Integer, Devs.BlankDev> srcToDev = new HashMap<>();
    static final Map<String, Devs.BlankDev> nameToDev = new HashMap<>();
    protected static final Set<Devs.Switch> switches = new HashSet<>();
    protected static final Set<Devs.LampSocket> lampsSockets = new HashSet<>();
    protected static final Set<Devs.EnvSensor> envSensors = new HashSet<>();
    protected static byte[] iterRequest;
    public static void main(String[] args) {
        serverURL = args[0];
        hubAddress = Long.parseLong(args[1], 16);
        iterRequest = new byte[0];
        HttpResponse<String> response = null;
        Set<Paket> pakets;
        Controller.startHub(response);

        while (true) {
            try {
                response = Controller.doRequest();
                Thread.sleep(coolDown);
            } catch (Controller.SmartHubExceptions.ErrorServerException | IOException | InterruptedException |
                     URISyntaxException e) {
                System.exit(99);
            } catch (Controller.SmartHubExceptions.EndSessionException e) {
                System.exit(0);
            }
            pakets = Controller.getSetPakets(response.body());
            Controller.updateClock(pakets);
            Controller.updateDevicesHere(pakets);
            Controller.dispatcher(pakets);
        }
    }
}

class Utils {
    public static byte computeCRC8(byte[] bytes) {
        final byte generator = 0x1D;
        byte crc = 0;

        for (byte currByte : bytes) {
            crc ^= currByte;
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
                if (value != 0)
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

class Paket {
    public static class Payload {
        public enum DevType {
            SMARTHUB, ENVSENSOR, SWITCH, LAMP, SOCKET, CLOCK
        }

        public enum Cmd {
            WHOISHERE, IAMHERE, GETSTATUS, STATUS, SETSTATUS, TICK
        }

        public final int src;
        public final int dst;
        public final long serial;
        public final DevType devType;
        public final Cmd cmd;
        public final byte[] cmdBody;

        public Payload(int src, int dst, long serial, byte devType, byte cmd, byte[] cmdBody) {
            this.src = src;
            this.dst = dst;
            this.serial = serial;
            this.devType = DevType.values()[devType - 1];
            this.cmd = Cmd.values()[cmd - 1];
            this.cmdBody = cmdBody;
        }

        public Payload(long src, Long dst, Long serial, DevType devType, Cmd cmd, byte[] cmdBody) {
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

    public static byte[] encodePaket(Payload payload) {
        byte[] payloadByte = Payload.encodePayload(payload);
        return Utils.concatByteArrays(
                new byte[]{(byte) payloadByte.length},
                payloadByte,
                new byte[]{Utils.computeCRC8(payloadByte)}
        );
    }

    public static Paket decodePaket(byte[] paket) {
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

class FabricPakets {
    public static class Hub {
        protected static byte[] encodeBlank(long dst, Paket.Payload.DevType devType,
                                            Paket.Payload.Cmd cmd, byte[] body) {
            return Paket.encodePaket(new Paket.Payload(SmartHub.hubAddress, dst, ++SmartHub.hubSerial, devType, cmd, body));
        }

        private static byte[] encodeBroadCast(Paket.Payload.Cmd cmd) {
            return Paket.encodePaket(
                    new Paket.Payload(SmartHub.hubAddress, 0x3FFFL, ++SmartHub.hubSerial, Paket.Payload.DevType.SMARTHUB, cmd,
                            Utils.encodeString(SmartHub.hubName)));
        }

        public static byte[] encodeWhoIsHere() {
            return encodeBroadCast(Paket.Payload.Cmd.WHOISHERE);
        }

        public static byte[] encodeIAmHere() {
            return encodeBroadCast(Paket.Payload.Cmd.IAMHERE);
        }

        public static byte[] encodeGetStatus(long dst, Paket.Payload.DevType devType) {
            return encodeBlank(dst, devType, Paket.Payload.Cmd.GETSTATUS, new byte[]{});
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

    public static class EnvSensor {
        public static class EnvSensorBody {
            public static class Trigger {
                final boolean operation;
                final boolean type;
                final int dNum;
                final int value;
                final String name;

                public Trigger(boolean operation, boolean type, int dNum, int value, String name) {
                    this.operation = operation;
                    this.type = type;
                    this.dNum = dNum;
                    this.value = value;
                    this.name = name;
                }

                @Override
                public String toString() {
                    return "Trigger{" +
                            "operation=" + operation +
                            ", type=" + type +
                            ", dNum=" + dNum +
                            ", value=" + value +
                            ", name='" + name + '\'' +
                            '}';
                }
            }

            final String name;
            final boolean dTemp;
            final boolean dHum;
            final boolean dLight;
            final boolean dAir;
            final List<Trigger> triggers;

            public EnvSensorBody(String name, boolean dTemp, boolean dHum, boolean dLight, boolean dAir,
                                 List<Trigger> triggers) {
                this.name = name;
                this.dTemp = dTemp;
                this.dHum = dHum;
                this.dLight = dLight;
                this.dAir = dAir;
                this.triggers = triggers;
            }

            @Override
            public String toString() {
                return "EnvSensorBody{" +
                        "dTemp=" + dTemp +
                        ", dHum=" + dHum +
                        ", dLight=" + dLight +
                        ", dAir=" + dAir +
                        ", triggers=" + triggers +
                        '}';
            }
        }

        private static boolean checkBit(byte bt, int num) {
            return ((bt >> num) % 2) == 1;
        }

        public static EnvSensorBody decodeEnvSensorGeneral(byte[] body) {
            int i = 0;
            String name = Utils.decodeString(Arrays.copyOfRange(body, i, i + Byte.toUnsignedInt(body[i]) + 1));
            i += Byte.toUnsignedInt(body[i]) + 1;
            byte sensors = body[i++];
            boolean dTemp = checkBit(sensors, 0);
            boolean dHum = checkBit(sensors, 1);
            boolean dLight = checkBit(sensors, 2);
            boolean dAir = checkBit(sensors, 3);
            int len = Byte.toUnsignedInt(body[i++]);
            List<EnvSensorBody.Trigger> triggers = new ArrayList<>();
            for (int it = 0; it < len; it++) {
                byte op = body[i++];
                boolean operation = checkBit(op, 0);
                boolean type = checkBit(op, 1);
                int dNum = (op >> 2) % 4;
                Utils.ULED128.DecodeResult result = Utils.ULED128.decodeULED128(body, i);
                i = result.to + 1;
                int value = Math.toIntExact(result.result);
                String devName = Utils.decodeString(Arrays.copyOfRange(body, i, i + Byte.toUnsignedInt(body[i]) + 1));
                i += Byte.toUnsignedInt(body[i]) + 1;
                triggers.add(new EnvSensorBody.Trigger(operation, type, dNum, value, devName));
            }
            return new EnvSensorBody(name, dTemp, dHum, dLight, dAir, triggers);
        }

        public static List<Integer> decodeEnvSensorStatus(byte[] body) {
            List<Integer> ls = new ArrayList<>();
            int i = 1;
            while (i < body.length) {
                Utils.ULED128.DecodeResult result = Utils.ULED128.decodeULED128(body, i);
                ls.add(Math.toIntExact(result.result));
                i = result.to + 1;
            }
            return ls;
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
    }
}

class Devs {
    public static abstract class BlankDev {
        public int src;
        public String name;
        public Paket.Payload.DevType devType;

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
        public List<String> list;

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

    public static class EnvSensor extends BlankDev {
        public FabricPakets.EnvSensor.EnvSensorBody sensorBody;

        public EnvSensor(int src, String name, FabricPakets.EnvSensor.EnvSensorBody sensorBody) {
            super(src, name, Paket.Payload.DevType.ENVSENSOR);
            this.sensorBody = sensorBody;
        }

        @Override
        public String toString() {
            return "EnvSensor{" +
                    "sensorBody=" + sensorBody +
                    ", src=" + src +
                    ", name='" + name + '\'' +
                    ", devType=" + devType +
                    '}';
        }
    }
}

class Controller {
    public static Set<Paket> getSetPakets(String string) {
        byte[] response = Base64.getUrlDecoder().decode(string);
        int i = 0;
        Set<Paket> pakets = new HashSet<>();
        while (i < response.length) {
            Paket paket = Paket.decodePaket(Arrays.copyOfRange(response, i, i + Byte.toUnsignedInt(response[i]) + 2));
            pakets.add(paket);
            i += Byte.toUnsignedInt(response[i]) + 2;
        }
        return pakets;
    }

    public static void updateClock(Set<Paket> pakets) {
        for (Paket paket : pakets) {
            if (paket.payload.cmd == Paket.Payload.Cmd.TICK) {
                SmartHub.time = FabricPakets.Clock.decodeClockTick(paket.payload.cmdBody);
                pakets.removeIf((paketTick -> paketTick.payload.cmd == Paket.Payload.Cmd.TICK));
                return;
            }
        }
    }

    public static <T extends Devs.BlankDev> void updateDevicesMaps(T dev, Set<T> set) {
        set.remove(dev);
        set.add(dev);
        SmartHub.srcToDev.put(dev.src, dev);
        SmartHub.nameToDev.put(dev.name, dev);
    }

    public static void addRequest(byte[] request) {
        SmartHub.iterRequest = Utils.concatByteArrays(SmartHub.iterRequest, request);
    }

    protected static class SmartHubExceptions {
        protected static class EndSessionException extends RuntimeException {
            public EndSessionException(String message) {
                super(message);
            }
        }

        protected static class ErrorServerException extends Error {
            public ErrorServerException(String message) {
                super(message);
            }
        }

    }

    protected static HttpResponse<String> doRequest() throws SmartHubExceptions.ErrorServerException,
            SmartHubExceptions.EndSessionException, IOException, InterruptedException, URISyntaxException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(SmartHub.serverURL))
                .POST(HttpRequest.BodyPublishers.ofString(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(SmartHub.iterRequest))
                )
                .build();
        SmartHub.iterRequest = new byte[0];
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        switch (response.statusCode()) {
            case 200 -> {
            }
            case 204 -> throw new SmartHubExceptions.EndSessionException("End of session.");
            default -> throw new SmartHubExceptions.ErrorServerException("Error server, status code: "
                    + response.statusCode());
        }
        return response;
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
                        FabricPakets.Switch.SwitchBody body =
                                FabricPakets.Switch.decodeSwitchGeneral(payload.cmdBody);
                        updateDevicesMaps(new Devs.Switch(payload.src, body.name, body.devs), SmartHub.switches);
                    }
                    case LAMP, SOCKET -> updateDevicesMaps(
                            new Devs.LampSocket(
                                    payload.src, FabricPakets.OneWayDev.decodeOneWayDevGeneral(payload.cmdBody),
                                    payload.devType
                            ),
                            SmartHub.lampsSockets
                    );
                    case ENVSENSOR -> {
                        FabricPakets.EnvSensor.EnvSensorBody body =
                                FabricPakets.EnvSensor.decodeEnvSensorGeneral(payload.cmdBody);
                        updateDevicesMaps(new Devs.EnvSensor(payload.src, body.name, body), SmartHub.envSensors);
                    }
                }
            }
        }
        pakets.removeAll(removePakets);
    }

    private static void checkValuesEnvSensor(Devs.EnvSensor sensor, int value, int number) {
        List<FabricPakets.EnvSensor.EnvSensorBody.Trigger> triggers =
                sensor.sensorBody.triggers.stream().filter(it -> it.dNum == number).toList();

        for (FabricPakets.EnvSensor.EnvSensorBody.Trigger trig : triggers) {
            if (!trig.type ? value < trig.value : value > trig.value) {
                Devs.BlankDev lampSocket = SmartHub.nameToDev.get(trig.name);
                addRequest(
                        FabricPakets.OneWayDev.encodeSetStatus(
                                lampSocket.src,
                                lampSocket.devType,
                                trig.operation ? 1 : 0)
                );
            }
        }
    }

    protected static void dispatcher(Set<Paket> pakets) {
        for (Paket.Payload payload : pakets.stream().map(it -> it.payload).toList()) {
            if(payload.cmd != Paket.Payload.Cmd.STATUS) continue;
            switch (payload.devType) {
                case ENVSENSOR -> {
                    Devs.EnvSensor sensor = (Devs.EnvSensor) SmartHub.srcToDev.get(payload.src);
                    List<Integer> values = FabricPakets.EnvSensor.decodeEnvSensorStatus(payload.cmdBody);
                    int i = 0;
                    if (sensor.sensorBody.dTemp) {
                        checkValuesEnvSensor(sensor, values.get(i), 0);
                        i++;
                    }
                    if (sensor.sensorBody.dHum) {
                        checkValuesEnvSensor(sensor, values.get(i), 1);
                        i++;
                    }
                    if (sensor.sensorBody.dLight) {
                        checkValuesEnvSensor(sensor, values.get(i), 2);
                        i++;
                    }
                    if (sensor.sensorBody.dAir) {
                        checkValuesEnvSensor(sensor, values.get(i), 3);
                    }
                }
                case SWITCH -> {
                    Devs.Switch swt = (Devs.Switch) SmartHub.srcToDev.get(payload.src);
                    for (String devs : swt.list) {
                        Devs.LampSocket lampSocket = (Devs.LampSocket) SmartHub.nameToDev.get(devs);
                        addRequest(
                                FabricPakets.OneWayDev.encodeSetStatus(
                                        lampSocket.src,
                                        lampSocket.devType,
                                        FabricPakets.Switch.decodeSwitchStatus(payload.cmdBody))
                        );
                    }
                }
            }
        }
    }

    public static void startHub(HttpResponse<String> response) {
        addRequest(FabricPakets.Hub.encodeWhoIsHere());
        try {
            response = doRequest();
        } catch (SmartHubExceptions.ErrorServerException | IOException | InterruptedException |
                 URISyntaxException e) {
            System.exit(99);
        } catch (SmartHubExceptions.EndSessionException e) {
            System.exit(0);
        }
        Set<Paket> pakets = getSetPakets(response.body());
        updateClock(pakets);
        Timestamp next_exit = new Timestamp(SmartHub.time.getTime() + SmartHub.waitTime);
        updateDevicesHere(pakets);

        while (!SmartHub.time.after(next_exit)) {
            try {
                response = doRequest();
                Thread.sleep(SmartHub.coolDown);
            } catch (SmartHubExceptions.ErrorServerException | IOException | InterruptedException |
                     URISyntaxException e) {
                System.exit(99);
            } catch (SmartHubExceptions.EndSessionException e) {
                System.exit(0);
            }
            pakets = getSetPakets(response.body());
            updateClock(pakets);
            updateDevicesHere(pakets);
        }
        for (Devs.BlankDev dev :
                Stream.concat(
                        Stream.concat(
                                SmartHub.switches.stream().map(it -> ((Devs.BlankDev) it)),
                                SmartHub.lampsSockets.stream().map(it -> ((Devs.BlankDev) it))),
                        SmartHub.envSensors.stream().map(it -> ((Devs.BlankDev) it))).toList()
        ) {
            addRequest(FabricPakets.Hub.encodeGetStatus(dev.src, dev.devType));
        }
    }
}

