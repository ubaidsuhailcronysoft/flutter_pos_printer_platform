#import <CoreBluetooth/CoreBluetooth.h>
#import <objc/runtime.h>

@implementation CBCentralManager (SafeCancel)

+ (void)load {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Class cls = [self class];
        Method original = class_getInstanceMethod(cls, @selector(cancelPeripheralConnection:));
        Method safe = class_getInstanceMethod(cls, @selector(safe_cancelPeripheralConnection:));
        method_exchangeImplementations(original, safe);
    });
}

- (void)safe_cancelPeripheralConnection:(CBPeripheral *)peripheral {
    if (peripheral == nil) {
        NSLog(@"[SafeCancel] Ignored cancelPeripheralConnection with nil peripheral");
        return;
    }
    [self safe_cancelPeripheralConnection:peripheral];
}

@end
