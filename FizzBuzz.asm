#FizzBuzz program
#incrementing essentials
li $t1, 1 #num to be incremented
li $t2, 101 #loop limit

#FizzBuzz essentials
li $t3, 3
li $t4, 5
li $t5, 15

#New line value
li $t9, '\n'

loop: #beginning of loop
rem $t0, $t1, $t5 #set the value of $t0 to $t1 % 15
beq $t0, 0, PrintFizzBuzz #if $t1 % 15 == 0, jump to PrintFizzBuzz

rem $t0, $t1, $t3 #set the value of $t0 to $t1 % 3
beq $t0, 0, PrintFizz #if $t1 % 15 == 0, jump to PrintFizzBuzz

rem $t0, $t1, $t4 #set the value of $t0 to $t1 % 5
beq $t0, 0, PrintBuzz #if $t1 % 15 == 0, jump to PrintFizzBuzz

j PrintInt #if $t1 is not currrently divisible by 3, 5, or 15, just print the current number

loopEnd: #second half of loop
addi $t1, $t1, 1 #increments $t1

#prints a new line
li $v0, 11
move $a0, $t9
syscall

bne $t1, $t2, loop #will jump back to loop if $t1 does not equal $t2
#in other words, will keep looping code until it has run $t2 - $t1 times

j Exit1 #begins traversal to end of code
#is split into parts to stay within 26-bit jumping range

PrintInt: #code that prints the current integer
move $a0, $t1
li $v0, 1
syscall
j loopEnd

PrintFizz: #code that prints "Fizz"
.text
la $a0, fizz
li $v0, 4
syscall
j loopEnd

#jump checkpoint
#used as stepping stone to reach end of code
#needed to stay within 26-bit address range
Exit1: 
j Exit2

PrintBuzz: #code that prints "Buzz"
.text
la $a0, buzz
li $v0, 4
syscall
j loopEnd

PrintFizzBuzz: #code that prints "FizzBuzz"
.text
la $a0, fizzBuzz
li $v0, 4
syscall
j loopEnd

Exit2: #end of code

#FizzBuzz keywords
#Placed here to avoid causing jumping errors
.data
fizz: .asciiz "Fizz"
buzz: .asciiz "Buzz"
fizzBuzz: .asciiz "FizzBuzz"
