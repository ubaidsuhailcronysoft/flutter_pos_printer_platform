#import "FlutterPosPrinterPlatformPlugin.h"
#import <CoreBluetooth/CoreBluetooth.h>

// iOS BLE printing via CoreBluetooth (GSDK ki jagah)
// connect tab tak jawab nahi deta jab tak printer ready na ho

@interface FlutterPosPrinterPlatformPlugin () <CBCentralManagerDelegate, CBPeripheralDelegate>
@property(nonatomic, retain) FlutterMethodChannel *channel;
@property(nonatomic, retain) BluetoothPrintStreamHandler *stateStreamHandler;

@property(nonatomic, strong) CBCentralManager *central;
@property(nonatomic, strong) NSMutableDictionary<NSString *, CBPeripheral *> *scannedPeripherals;
@property(nonatomic, assign) BOOL pendingScan;

@property(nonatomic, strong) CBPeripheral *activePeripheral;
@property(nonatomic, strong) CBCharacteristic *writeChar;
@property(nonatomic, assign) NSInteger servicesPending;
@property(nonatomic, strong) NSTimer *connectTimer;
@property(nonatomic, copy) FlutterResult pendingConnectResult;

@property(nonatomic, strong) NSMutableArray<NSData *> *writeQueue;
@property(nonatomic, assign) BOOL waitingForWriteAck;
@end

@implementation FlutterPosPrinterPlatformPlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  FlutterMethodChannel* channel = [FlutterMethodChannel
      methodChannelWithName:NAMESPACE @"/methods"
            binaryMessenger:[registrar messenger]];
  FlutterEventChannel* stateChannel = [FlutterEventChannel eventChannelWithName:NAMESPACE @"/state"
                                                                binaryMessenger:[registrar messenger]];
  FlutterPosPrinterPlatformPlugin* instance = [[FlutterPosPrinterPlatformPlugin alloc] init];

  instance.channel = channel;
  instance.scannedPeripherals = [NSMutableDictionary new];
  instance.writeQueue = [NSMutableArray new];

  BluetoothPrintStreamHandler* stateStreamHandler = [[BluetoothPrintStreamHandler alloc] init];
  [stateChannel setStreamHandler:stateStreamHandler];
  instance.stateStreamHandler = stateStreamHandler;

  [registrar addMethodCallDelegate:instance channel:channel];
}

#pragma mark - Helpers

- (CBCentralManager *)ensureCentral {
  if (self.central == nil) {
    self.central = [[CBCentralManager alloc] initWithDelegate:self
                                                        queue:nil
                                                      options:@{CBCentralManagerOptionShowPowerAlertKey: @YES}];
  }
  return self.central;
}

// 0 = disconnected/failed, 1 = connecting, 2 = connected
- (void)sendState:(NSInteger)value {
  dispatch_async(dispatch_get_main_queue(), ^{
    NSLog(@"status -> %ld", (long)value);
    if (self.stateStreamHandler.sink != nil) {
      self.stateStreamHandler.sink(@(value));
    }
    // connect ka intezaar kar rahe Dart ko jawab do
    if (self.pendingConnectResult != nil && value != 1) {
      FlutterResult r = self.pendingConnectResult;
      self.pendingConnectResult = nil;
      r(@(value == 2));
    }
  });
}

- (void)stopConnectTimer {
  [self.connectTimer invalidate];
  self.connectTimer = nil;
}

- (void)resetConnection {
  [self stopConnectTimer];
  self.writeChar = nil;
  self.servicesPending = 0;
  self.waitingForWriteAck = NO;
  [self.writeQueue removeAllObjects];
}

- (void)failConnection:(NSString *)reason {
  NSLog(@"connect failed: %@", reason);
  CBPeripheral *p = self.activePeripheral;
  [self resetConnection];
  self.activePeripheral = nil;
  if (p != nil) {
    [self.central cancelPeripheralConnection:p];
  }
  [self sendState:0];
}

- (void)doStartScan {
  [self.scannedPeripherals removeAllObjects];
  [self.central scanForPeripheralsWithServices:nil
                                       options:@{CBCentralManagerScanOptionAllowDuplicatesKey: @NO}];
  NSLog(@"scan started");
}

#pragma mark - Method channel

- (void)handleMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
  NSLog(@"call method -> %@", call.method);

  if ([@"state" isEqualToString:call.method]) {
    result(nil);

  } else if ([@"isAvailable" isEqualToString:call.method]) {
    result(@(YES));

  } else if ([@"isOn" isEqualToString:call.method]) {
    result(@([self ensureCentral].state == CBManagerStatePoweredOn));

  } else if ([@"isConnected" isEqualToString:call.method]) {
    result(@(self.writeChar != nil));

  } else if ([@"startScan" isEqualToString:call.method]) {
    [self ensureCentral];
    if (self.central.state == CBManagerStatePoweredOn) {
      [self doStartScan];
    } else {
      self.pendingScan = YES;
    }
    result(nil);

  } else if ([@"stopScan" isEqualToString:call.method]) {
    self.pendingScan = NO;
    if (self.central.isScanning) [self.central stopScan];
    result(nil);

  } else if ([@"connect" isEqualToString:call.method]) {
    NSDictionary *device = [call arguments];
    NSString *address = device[@"address"];
    NSLog(@"connect device begin -> %@ | %@", device[@"name"], address);
    [self ensureCentral];

    // Pehle se isi printer se connected
    if (self.activePeripheral != nil && self.writeChar != nil &&
        [self.activePeripheral.identifier.UUIDString isEqualToString:address]) {
      [self sendState:2];
      result(@(YES));
      return;
    }

    // Scan list se, warna saved UUID se iOS se maango
    CBPeripheral *peripheral = self.scannedPeripherals[address];
    if (peripheral == nil && address.length > 0) {
      NSUUID *uuid = [[NSUUID alloc] initWithUUIDString:address];
      if (uuid != nil) {
        peripheral = [self.central retrievePeripheralsWithIdentifiers:@[uuid]].firstObject;
      }
    }
    if (peripheral == nil) {
      NSLog(@"connect: peripheral NOT found for %@", address);
      result(@(NO));
      return;
    }

    // Purana connection band
    if (self.activePeripheral != nil && self.activePeripheral != peripheral) {
      [self.central cancelPeripheralConnection:self.activePeripheral];
    }
    [self resetConnection];

    // Pichhla connect abhi bhi wait kar raha ho to use fail bata do
    if (self.pendingConnectResult != nil) {
      self.pendingConnectResult(@(NO));
      self.pendingConnectResult = nil;
    }

    if (self.central.isScanning) [self.central stopScan];

    self.activePeripheral = peripheral;
    peripheral.delegate = self;
    [self.central connectPeripheral:peripheral options:nil];

    self.connectTimer = [NSTimer scheduledTimerWithTimeInterval:10.0
                                                         target:self
                                                       selector:@selector(onConnectTimeout)
                                                       userInfo:nil
                                                        repeats:NO];

    // connected ya fail hone par sendState se jawab jayega
    self.pendingConnectResult = result;

  } else if ([@"disconnect" isEqualToString:call.method]) {
    if (self.activePeripheral != nil) {
      [self.central cancelPeripheralConnection:self.activePeripheral];
    }
    result(nil);

  } else if ([@"writeData" isEqualToString:call.method]) {
    NSDictionary *args = [call arguments];
    NSArray *bytes = args[@"bytes"];
    NSInteger len = [args[@"length"] integerValue];

    if (self.activePeripheral == nil || self.writeChar == nil) {
      NSLog(@"writeData: printer not connected");
      result(nil);
      return;
    }

    NSMutableData *data = [NSMutableData dataWithLength:len];
    uint8_t *buf = data.mutableBytes;
    for (NSInteger i = 0; i < len; i++) {
      buf[i] = (uint8_t)[bytes[i] intValue];
    }

    [self enqueueData:data];
    result(nil);

  } else {
    result(FlutterMethodNotImplemented);
  }
}

- (void)onConnectTimeout {
  [self failConnection:@"timeout"];
}

#pragma mark - Writing (chunked, flow-controlled)

- (CBCharacteristicWriteType)writeType {
  return (self.writeChar.properties & CBCharacteristicPropertyWriteWithoutResponse)
           ? CBCharacteristicWriteWithoutResponse
           : CBCharacteristicWriteWithResponse;
}

- (void)enqueueData:(NSData *)data {
  NSUInteger chunk = [self.activePeripheral maximumWriteValueLengthForType:[self writeType]];
  if (chunk < 20) chunk = 20;
  if (chunk > 180) chunk = 180;

  for (NSUInteger offset = 0; offset < data.length; offset += chunk) {
    NSUInteger size = MIN(chunk, data.length - offset);
    [self.writeQueue addObject:[data subdataWithRange:NSMakeRange(offset, size)]];
  }
  [self pumpWrites];
}

- (void)pumpWrites {
  if (self.activePeripheral == nil || self.writeChar == nil) return;

  CBCharacteristicWriteType type = [self writeType];

  while (self.writeQueue.count > 0) {
    if (type == CBCharacteristicWriteWithResponse) {
      if (self.waitingForWriteAck) return;
      self.waitingForWriteAck = YES;
      NSData *next = self.writeQueue.firstObject;
      [self.writeQueue removeObjectAtIndex:0];
      [self.activePeripheral writeValue:next forCharacteristic:self.writeChar type:type];
      return;
    } else {
      if (@available(iOS 11.0, *)) {
        if (!self.activePeripheral.canSendWriteWithoutResponse) return;
      }
      NSData *next = self.writeQueue.firstObject;
      [self.writeQueue removeObjectAtIndex:0];
      [self.activePeripheral writeValue:next forCharacteristic:self.writeChar type:type];
    }
  }
}

#pragma mark - CBCentralManagerDelegate

- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
  NSLog(@"bluetooth state -> %ld", (long)central.state);
  if (central.state == CBManagerStatePoweredOn) {
    if (self.pendingScan) {
      self.pendingScan = NO;
      [self doStartScan];
    }
  } else if (self.activePeripheral != nil) {
    [self resetConnection];
    self.activePeripheral = nil;
    [self sendState:0];
  }
}

- (void)centralManager:(CBCentralManager *)central
 didDiscoverPeripheral:(CBPeripheral *)peripheral
     advertisementData:(NSDictionary<NSString *,id> *)advertisementData
                  RSSI:(NSNumber *)RSSI {
  NSString *name = peripheral.name ?: advertisementData[CBAdvertisementDataLocalNameKey];
  if (name == nil) return;

  NSString *address = peripheral.identifier.UUIDString;
  BOOL isNew = (self.scannedPeripherals[address] == nil);
  self.scannedPeripherals[address] = peripheral;

  if (isNew) {
    NSLog(@"find device -> %@ | %@", name, address);
    [self.channel invokeMethod:@"ScanResult" arguments:@{@"address": address, @"name": name}];
  }
}

- (void)centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral {
  NSLog(@"BLE connected, discovering services...");
  [peripheral discoverServices:nil];
}

- (void)centralManager:(CBCentralManager *)central
didFailToConnectPeripheral:(CBPeripheral *)peripheral
                 error:(NSError *)error {
  [self failConnection:error.localizedDescription ?: @"failed to connect"];
}

- (void)centralManager:(CBCentralManager *)central
didDisconnectPeripheral:(CBPeripheral *)peripheral
                 error:(NSError *)error {
  NSLog(@"disconnected %@", error.localizedDescription ?: @"");
  if (peripheral == self.activePeripheral) {
    [self resetConnection];
    self.activePeripheral = nil;
    [self sendState:0];
  }
}

#pragma mark - CBPeripheralDelegate

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {
  if (error != nil || peripheral.services.count == 0) {
    [self failConnection:@"no services"];
    return;
  }
  self.servicesPending = peripheral.services.count;
  for (CBService *service in peripheral.services) {
    NSLog(@"service -> %@", service.UUID.UUIDString);
    [peripheral discoverCharacteristics:nil forService:service];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
didDiscoverCharacteristicsForService:(CBService *)service
             error:(NSError *)error {
  self.servicesPending--;

  BOOL isDeviceInfo = [service.UUID isEqual:[CBUUID UUIDWithString:@"180A"]];

  if (self.writeChar == nil && !isDeviceInfo) {
    CBCharacteristic *withResponse = nil;
    for (CBCharacteristic *c in service.characteristics) {
      if (c.properties & CBCharacteristicPropertyWriteWithoutResponse) {
        self.writeChar = c;
        break;
      }
      if ((c.properties & CBCharacteristicPropertyWrite) && withResponse == nil) {
        withResponse = c;
      }
    }
    if (self.writeChar == nil) self.writeChar = withResponse;

    if (self.writeChar != nil) {
      NSLog(@"printer ready: service %@, write char %@",
            service.UUID.UUIDString, self.writeChar.UUID.UUIDString);
      [self stopConnectTimer];
      [self sendState:2];
    }
  }

  if (self.servicesPending <= 0 && self.writeChar == nil) {
    [self failConnection:@"no writable characteristic"];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
didWriteValueForCharacteristic:(CBCharacteristic *)characteristic
             error:(NSError *)error {
  if (error != nil) NSLog(@"write error: %@", error.localizedDescription);
  self.waitingForWriteAck = NO;
  [self pumpWrites];
}

- (void)peripheralIsReadyToSendWriteWithoutResponse:(CBPeripheral *)peripheral {
  [self pumpWrites];
}

@end

@implementation BluetoothPrintStreamHandler

- (FlutterError*)onListenWithArguments:(id)arguments eventSink:(FlutterEventSink)eventSink {
  self.sink = eventSink;
  return nil;
}

- (FlutterError*)onCancelWithArguments:(id)arguments {
  self.sink = nil;
  return nil;
}

@end
