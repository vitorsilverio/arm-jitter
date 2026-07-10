package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class GuestToHostMapperTest {
    @Test
    void bindingsMatchPublicJvmApis() throws NoSuchMethodException {
        assertMethod(ArmCore.class, GuestToHostMapper.registerRead());
        assertMethod(ArmCore.class, GuestToHostMapper.registerWrite());
        assertMethod(ArmCore.class, GuestToHostMapper.programCounterRead());
        assertMethod(ArmCore.class, GuestToHostMapper.programCounterWrite());
        assertMethod(ArmCore.class, GuestToHostMapper.cpsr());
        assertMethod(ArmCore.class, GuestToHostMapper.setCpsr());
        assertMethod(ArmCore.class, GuestToHostMapper.mode());
        assertMethod(ArmCore.class, GuestToHostMapper.memory());
        assertMethod(ArmCore.class, GuestToHostMapper.addCycles());
        assertMethod(ArmCore.class, GuestToHostMapper.addMemoryCycles());

        assertMethod(CpsrRegister.class, GuestToHostMapper.cpsrGet());
        assertMethod(CpsrRegister.class, GuestToHostMapper.cpsrSet());
        assertMethod(CpsrRegister.class, GuestToHostMapper.cpsrSetNzcv());
        assertMethod(CpsrRegister.class, GuestToHostMapper.cpsrCarry());
        assertMethod(CpsrRegister.class, GuestToHostMapper.cpsrSetThumbMode());

        assertMethod(AddressSpace.class, GuestToHostMapper.memoryRead8());
        assertMethod(AddressSpace.class, GuestToHostMapper.memoryRead16());
        assertMethod(AddressSpace.class, GuestToHostMapper.memoryRead32());
        assertMethod(AddressSpace.class, GuestToHostMapper.memoryWrite8());
        assertMethod(AddressSpace.class, GuestToHostMapper.memoryWrite16());
        assertMethod(AddressSpace.class, GuestToHostMapper.memoryWrite32());
        assertMethod(AddressSpace.class, GuestToHostMapper.memoryAccessCycles());
    }

    @Test
    void standardJvmEmittersExposeSameBindings() {
        StandardJvmEmitters emitters = StandardJvmEmitters.INSTANCE;
        assertNotNull(emitters.cpsr());
        assertNotNull(emitters.setNzcv());
        assertNotNull(emitters.memory());
        assertNotNull(emitters.addMemoryCycles());
    }

    private static void assertMethod(Class<?> owner, HostMethodBinding binding) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(binding.name())) {
                String descriptor = org.objectweb.asm.Type.getMethodDescriptor(method);
                if (descriptor.equals(binding.descriptor())) {
                    return;
                }
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + binding.name() + binding.descriptor());
    }
}
